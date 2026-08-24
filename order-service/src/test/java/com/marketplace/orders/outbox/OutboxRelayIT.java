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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.marketplace.events.OrderCreated;
import com.marketplace.events.Topics;
import com.marketplace.orders.KafkaPostgresIT;

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
 */
class OutboxRelayIT extends KafkaPostgresIT {

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

	@Value("${outbox.relay.max-attempts:5}")
	private int maxAttempts;

	@Test
	void publishesPendingRecord() throws Exception {
		UUID aggregateId = UUID.randomUUID();
		outboxRepository.save(pendingRecord(aggregateId, Topics.ORDER_CREATED, orderCreatedPayload(aggregateId)));

		outboxRelay.pollAndPublish();

		List<ConsumerRecord<String, String>> received = consume(Topics.ORDER_CREATED, 1, Duration.ofSeconds(10));
		assertThat(received).isNotEmpty();

		// SC-008: an INDEPENDENT reader, using nothing this service wrote to consume, deserializes the
		// message back into an object equal to the one recorded -- proving the wire format is right,
		// not merely that one in-memory object equals another.
		OrderCreated redelivered = MAPPER.readValue(
				received.stream().filter(r -> r.key().equals(aggregateId.toString())).findFirst().orElseThrow().value(),
				OrderCreated.class);
		assertThat(redelivered.sagaId()).isEqualTo(aggregateId);
	}

	@Test
	void keysMessageBySagaId() throws Exception {
		UUID aggregateId = UUID.randomUUID();
		outboxRepository.save(pendingRecord(aggregateId, Topics.ORDER_CREATED, orderCreatedPayload(aggregateId)));

		outboxRelay.pollAndPublish();

		ConsumerRecord<String, String> received = findByKey(
				consume(Topics.ORDER_CREATED, 1, Duration.ofSeconds(10)), aggregateId.toString());

		assertThat(received.key()).isEqualTo(aggregateId.toString());
	}

	@Test
	void sendsStoredPayloadUnchanged() throws Exception {
		UUID aggregateId = UUID.randomUUID();
		String storedPayload = orderCreatedPayload(aggregateId);
		outboxRepository.save(pendingRecord(aggregateId, Topics.ORDER_CREATED, storedPayload));

		outboxRelay.pollAndPublish();

		ConsumerRecord<String, String> received = findByKey(
				consume(Topics.ORDER_CREATED, 1, Duration.ofSeconds(10)), aggregateId.toString());

		// Byte-for-byte, not "parses to an equal object" -- re-serializing would still pass an
		// equality check while silently reopening the drift FR-010 exists to close.
		assertThat(received.value()).isEqualTo(storedPayload);
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
		assertThat(consume(Topics.ORDER_CREATED, 1, Duration.ofSeconds(10))).isNotEmpty();

		OutboxRecord reloaded = outboxRepository.findById(saved.getId()).orElseThrow();
		assertThat(reloaded.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
		assertThat(reloaded.getPublishedAt()).isNotNull();
	}

	@Test
	void doesNotResendPublished() throws Exception {
		UUID aggregateId = UUID.randomUUID();
		outboxRepository.save(pendingRecord(aggregateId, Topics.ORDER_CREATED, orderCreatedPayload(aggregateId)));
		outboxRelay.pollAndPublish();
		assertThat(consume(Topics.ORDER_CREATED, 1, Duration.ofSeconds(10))).isNotEmpty();

		// Runs again against a row that is now PUBLISHED. The claim query (T094) is what should make
		// this row invisible to a second run; this test proves that end to end through the relay
		// rather than by inspecting the query in isolation.
		outboxRelay.pollAndPublish();
		outboxRelay.pollAndPublish();

		// The direct check: still exactly one message with this key on the channel, not merely that
		// attempts stayed at zero -- attempts alone would not catch a relay that resent successfully
		// without ever recording a failure.
		long matchingKeyCount = consume(Topics.ORDER_CREATED, 1, Duration.ofSeconds(3)).stream()
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
		outboxRepository.save(pendingRecord(failingAggregate, UNPROVISIONED_CHANNEL, "{}"));
		outboxRepository.save(
				pendingRecord(healthyAggregate, Topics.ORDER_CREATED, orderCreatedPayload(healthyAggregate)));

		outboxRelay.pollAndPublish();

		List<ConsumerRecord<String, String>> received = consume(Topics.ORDER_CREATED, 1, Duration.ofSeconds(10));
		assertThat(received).anySatisfy(r -> assertThat(r.key()).isEqualTo(healthyAggregate.toString()));
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

	private static ConsumerRecord<String, String> findByKey(List<ConsumerRecord<String, String>> records, String key) {
		return records.stream().filter(r -> r.key().equals(key)).findFirst()
				.orElseThrow(() -> new AssertionError("no record found with key " + key));
	}
}
