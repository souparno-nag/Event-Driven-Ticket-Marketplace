package com.marketplace.orders.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.marketplace.events.OrderCreated;
import com.marketplace.events.Topics;

/**
 * Specifies guarantees 1–8 of {@code contracts/outbox-relay.md} — the core behaviour the developer's
 * {@code OutboxRelay.pollAndPublish()} (T099) must provide, against an {@code OutboxRelay} class T097
 * has not yet created.
 *
 * <p>Will not compile until {@code OutboxRelay} exists. That is the intended state — see
 * {@code CreateOrderRequestValidationTest} (T073) for why a compile failure is the correct "red" for
 * a test like this in a statically typed language.
 *
 * <p>Rows are built directly against {@link OutboxRepository}, never through a real {@code Order} —
 * the outbox table's {@code aggregate_id} deliberately carries no foreign key (data-model.md), so
 * these tests, which are about the relay's mechanics rather than the mapping that produces a row,
 * need no order to exist at all.
 *
 * <p>Extends {@link RelayDrivenIT}, not {@code KafkaPostgresIT} directly — see that class for why
 * these tests need the background scheduler suppressed, and why that suppression lives on one shared
 * class rather than being declared separately here.
 */
class OutboxRelayIT extends RelayDrivenIT {

	// A no-topic-provisioned channel name — KafkaPostgresIT disables auto-creation, so a send aimed
	// here genuinely fails against the real broker, which is what lets guarantees 6-8 be tested
	// against real failure rather than a mocked one.
	private static final String UNPROVISIONED_CHANNEL = "no.such.channel";

	private static final ObjectMapper MAPPER = JsonMapper.builder()
			.addModule(new JavaTimeModule())
			.enable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN)
			.build();

	@Autowired
	private OutboxRelay outboxRelay;

	@Autowired
	private OutboxRepository outboxRepository;

	@Autowired
	private JdbcTemplate jdbc;

	@Value("${outbox.relay.max-attempts:5}")
	private int maxAttempts;

	@Test
	void publishesPendingRecord() throws Exception {
		UUID aggregateId = UUID.randomUUID();
		outboxRepository.save(pendingRecord(aggregateId, Topics.ORDER_CREATED, orderCreatedPayload(aggregateId)));

		outboxRelay.pollAndPublish();

		// consumeUntil, not consume(topic, 1, ...): this topic is shared across every test in this
		// class, so a plain "wait for 1 record" would happily stop on an earlier test's leftover
		// message and never reach this one. Searching specifically for THIS aggregate id's key is
		// what makes the wait actually about this test's own message.
		ConsumerRecord<String, String> received = findByKey(aggregateId);

		// SC-008: an INDEPENDENT reader, using nothing this service wrote to consume, deserializes the
		// message back into an object equal to the one recorded -- proving the wire format is right,
		// not merely that one in-memory object equals another.
		OrderCreated redelivered = MAPPER.readValue(received.value(), OrderCreated.class);
		assertThat(redelivered.sagaId()).isEqualTo(aggregateId);
	}

	@Test
	void keysMessageBySagaId() throws Exception {
		UUID aggregateId = UUID.randomUUID();
		outboxRepository.save(pendingRecord(aggregateId, Topics.ORDER_CREATED, orderCreatedPayload(aggregateId)));

		outboxRelay.pollAndPublish();

		ConsumerRecord<String, String> received = findByKey(aggregateId);

		assertThat(received.key()).isEqualTo(aggregateId.toString());
	}

	@Test
	void sendsStoredPayloadUnchanged() throws Exception {
		UUID aggregateId = UUID.randomUUID();
		String storedPayload = orderCreatedPayload(aggregateId);
		outboxRepository.save(pendingRecord(aggregateId, Topics.ORDER_CREATED, storedPayload));

		outboxRelay.pollAndPublish();

		ConsumerRecord<String, String> received = findByKey(aggregateId);

		// Compared as PARSED documents, not raw bytes -- research.md R7 already accepted, when jsonb
		// was chosen for the payload column, that PostgreSQL normalises a jsonb value's key order and
		// whitespace on the way back out. Asserting byte-for-byte equality here would be testing
		// against a guarantee this project deliberately did not make. What FR-010 actually forbids is
		// RE-SERIALIZING the content -- parsing the payload and writing a NEW document from it, which
		// would reopen the money-formatting drift WRITE_BIGDECIMAL_AS_PLAIN (T070) exists to close.
		// Parsed equality catches exactly that: any lost or altered field, any value that changed
		// shape, while tolerating jsonb's cosmetic reordering.
		assertThat(MAPPER.readValue(received.value(), OrderCreated.class))
				.isEqualTo(MAPPER.readValue(storedPayload, OrderCreated.class));
	}

	@Test
	void marksPublishedOnlyAfterAck() throws Exception {
		UUID aggregateId = UUID.randomUUID();
		OutboxRecord saved = outboxRepository.save(
				pendingRecord(aggregateId, Topics.ORDER_CREATED, orderCreatedPayload(aggregateId)));

		outboxRelay.pollAndPublish();

		// Both facts checked together: the row says PUBLISHED, and the message really is retrievable
		// from the broker. A relay that marked the row without awaiting the broker's acknowledgement
		// would still often pass this on a fast local broker; guarantee 6's failure-path test is what
		// actually catches that anti-pattern, by proving the FAILURE side is never misreported as
		// success. This test establishes the success side agrees.
		findByKey(aggregateId);

		OutboxRecord reloaded = outboxRepository.findById(saved.getId()).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
		assertThat(reloaded.getPublishedAt()).isNotNull();
	}

	@Test
	void doesNotResendPublished() throws Exception {
		UUID aggregateId = UUID.randomUUID();
		outboxRepository.save(pendingRecord(aggregateId, Topics.ORDER_CREATED, orderCreatedPayload(aggregateId)));
		outboxRelay.pollAndPublish();
		findByKey(aggregateId);

		// Runs again against a row that is now PUBLISHED. The claim query (T094) is what should make
		// this row invisible to a second run; this test proves that end to end through the relay
		// rather than by inspecting the query in isolation.
		outboxRelay.pollAndPublish();
		outboxRelay.pollAndPublish();

		// The direct check: still exactly one message with this key on the channel, not merely that
		// attempts stayed at zero -- attempts alone would not catch a relay that resent successfully
		// without ever recording a failure. Both re-runs above already happened before this consumer
		// even connects, so if a duplicate existed it is already sitting in the log; consumeUntil's
		// very first poll() call fetches everything currently available on the partition in one
		// batch, which is why searching for "any match" here still reliably picks up a duplicate
		// alongside the original rather than stopping short of it.
		long matchingKeyCount = consumeUntil(Topics.ORDER_CREATED, Duration.ofSeconds(10),
				r -> r.key().equals(aggregateId.toString()))
				.stream()
				.filter(r -> r.key().equals(aggregateId.toString()))
				.count();
		assertThat(matchingKeyCount).isEqualTo(1);
	}

	@Test
	void retainsFailedRecordForRetry() {
		UUID aggregateId = UUID.randomUUID();
		OutboxRecord saved = outboxRepository.save(pendingRecord(aggregateId, UNPROVISIONED_CHANNEL, "{}"));

		outboxRelay.pollAndPublish();

		OutboxRecord reloaded = outboxRepository.findById(saved.getId()).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(OutboxStatus.PENDING);
		assertThat(reloaded.getAttempts()).isEqualTo(1);
		assertThat(reloaded.getLastError()).isNotNull();

		// This row is DELIBERATELY left PENDING by this test's own assertion above -- that is exactly
		// what guarantee 6 requires. Left as-is, it would still be claimable by every subsequent
		// pollAndPublish() call for the rest of this class (and any other class sharing this
		// database), each paying this same channel's send-failure delay for a row no other test
		// cares about. Parking it directly bypasses the relay entirely -- parking-via-the-relay is
		// already covered on its own by parksAfterMaxAttempts -- and is purely test cleanup.
		//
		// A plain JDBC UPDATE rather than outboxRepository.save(reloaded.park()): saving through
		// Hibernate here raced with something else still holding this row (most likely the relay's own
		// still-open claim transaction finishing its OWN commit), producing an intermittent
		// QueryTimeoutException from Spring's transaction timeout cancelling the statement while it
		// waited on the row lock. A direct, tiny UPDATE has nothing to wait on for long.
		jdbc.update("UPDATE outbox SET status = 'PARKED' WHERE id = ?", reloaded.getId());
	}

	@Test
	void parksAfterMaxAttempts() {
		UUID aggregateId = UUID.randomUUID();
		OutboxRecord saved = outboxRepository.save(pendingRecord(aggregateId, UNPROVISIONED_CHANNEL, "{}"));

		for (int i = 0; i < maxAttempts; i++) {
			outboxRelay.pollAndPublish();
		}

		OutboxRecord reloaded = outboxRepository.findById(saved.getId()).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(OutboxStatus.PARKED);
		assertThat(reloaded.getAttempts()).isEqualTo(maxAttempts);

		// One more run must not touch a parked row at all -- its attempt count must stop moving.
		outboxRelay.pollAndPublish();
		assertThat(outboxRepository.findById(saved.getId()).orElseThrow().getAttempts()).isEqualTo(maxAttempts);
	}

	@Test
	void oneFailureDoesNotStopTheBatch() throws Exception {
		UUID failingAggregate = UUID.randomUUID();
		UUID healthyAggregate = UUID.randomUUID();
		OutboxRecord failing = outboxRepository.save(pendingRecord(failingAggregate, UNPROVISIONED_CHANNEL, "{}"));
		outboxRepository.save(
				pendingRecord(healthyAggregate, Topics.ORDER_CREATED, orderCreatedPayload(healthyAggregate)));

		outboxRelay.pollAndPublish();

		findByKey(healthyAggregate);

		// See retainsFailedRecordForRetry for why this is a direct JDBC update rather than
		// outboxRepository.save(reloaded.park()): this row is left PENDING on purpose by design, and
		// parking it here is cleanup, not a claim about what the relay itself did with it.
		OutboxRecord reloaded = outboxRepository.findById(failing.getId()).orElseThrow();
		jdbc.update("UPDATE outbox SET status = 'PARKED' WHERE id = ?", reloaded.getId());
	}

	// --- fixtures -----------------------------------------------------------------------------

	private static OutboxRecord pendingRecord(UUID aggregateId, String eventType, String payload) {
		return new OutboxRecord(aggregateId, eventType, payload, null, null);
	}

	private static String orderCreatedPayload(UUID aggregateId) throws Exception {
		OrderCreated event = new OrderCreated(
				UUID.randomUUID(), aggregateId, Instant.now(), 1,
				aggregateId, UUID.randomUUID(), UUID.randomUUID(),
				List.of("A1"), new BigDecimal("42.00"));
		return MAPPER.writeValueAsString(event);
	}

	/**
	 * Waits until a message keyed by {@code aggregateId} appears on {@code order.created} — never
	 * "wait for N records", since the topic is shared with every other test in this class and a
	 * count-based wait would happily stop on someone else's message first.
	 */
	private static ConsumerRecord<String, String> findByKey(UUID aggregateId) {
		String key = aggregateId.toString();
		return consumeUntil(Topics.ORDER_CREATED, Duration.ofSeconds(10), r -> r.key().equals(key))
				.stream().filter(r -> r.key().equals(key)).findFirst()
				.orElseThrow(() -> new AssertionError("no record found with key " + key));
	}
}
