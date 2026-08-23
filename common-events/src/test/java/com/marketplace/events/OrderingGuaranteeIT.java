package com.marketplace.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Messages for one order are consumed in the order they were produced, across 100 orders published
 * concurrently (SC-011, FR-026, FR-027).
 *
 * <p>This runs against a real broker rather than a mock, because the behaviour under test belongs to
 * Kafka rather than to this module. Keying, partition assignment, and per-partition ordering are
 * broker properties; a mock would only prove that the test's own fake behaves the way the test
 * expects, which is worth nothing.
 *
 * <p><b>The exact guarantee being tested.</b> Kafka orders messages within a single
 * <em>topic-partition</em>, not within a topic and not across topics. Keying by saga id sends every
 * message for one order to the same partition, which is what converts Kafka's narrow guarantee into
 * the per-order guarantee FR-026 needs — and three partitions let unrelated orders proceed
 * independently (FR-027). The two requirements are the same mechanism seen from two directions.
 *
 * <p><b>What this does NOT prove, stated so it is not assumed.</b> FR-026 is worded as though all of
 * one order's messages are ordered relative to each other, but the saga publishes each message type
 * to its own channel, so one order's messages are spread across seven topics with no ordering
 * relationship between them. The saga is still correct, because each step is <em>caused by</em>
 * consuming the previous one — {@code SeatsReserved} cannot be published until {@code OrderCreated}
 * has been handled, so causality sequences the saga and Kafka never has to. The place it will matter
 * is a consumer reading several channels at once, which arrives with the projection service in build
 * step 6: it can legitimately observe {@code OrderConfirmed} before {@code SeatsReserved} for the
 * same order if it happens to be behind on one channel, and it must be written to tolerate that.
 * This test covers the guarantee that exists; the note exists so the other one is not relied on.
 */
@Testcontainers
class OrderingGuaranteeIT {

	private static final int ORDERS = 100;
	private static final int MESSAGES_PER_ORDER = 5;
	private static final int PUBLISHER_THREADS = 8;
	private static final int PARTITIONS = 3;
	private static final int TOTAL_MESSAGES = ORDERS * MESSAGES_PER_ORDER;

	/** A real channel from the contracts, so the test exercises the name the system actually uses. */
	private static final String TOPIC = Topics.ORDER_CREATED;

	// Pinned to the exact image the environment runs (T029), so this test needs no download beyond
	// what `make up` already pulled, and cannot pass against a broker version the project does not use.
	@Container
	private static final KafkaContainer KAFKA =
			new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

	/** What was published, per order, in production order. The expected answer. */
	private static Map<UUID, List<UUID>> produced;

	/** What came back, in consumption order. */
	private static List<Received> received;

	/** One consumed message, reduced to the three facts this test reasons about. */
	private record Received(int partition, UUID sagaId, UUID messageId) {
	}

	@BeforeAll
	static void publish_concurrently_then_consume_everything() throws Exception {
		createTopic();
		produced = publish();
		received = consume();
	}

	// --- the assertions ---------------------------------------------------------------------------

	@Test
	@DisplayName("every order's messages are consumed in exactly the order they were produced")
	void per_order_ordering_holds() {
		Map<UUID, List<UUID>> consumedPerOrder = new LinkedHashMap<>();
		for (Received record : received) {
			consumedPerOrder.computeIfAbsent(record.sagaId(), key -> new ArrayList<>()).add(record.messageId());
		}

		assertThat(consumedPerOrder)
				.as("every order that was published came back")
				.hasSize(ORDERS);

		// Compared as whole lists rather than element by element, so a failure names the order and
		// shows both sequences — which is the difference between "test failed" and a diagnosis.
		assertThat(consumedPerOrder).allSatisfy((sagaId, consumedIds) ->
				assertThat(consumedIds)
						.as("messages for order %s", sagaId)
						.containsExactlyElementsOf(produced.get(sagaId)));
	}

	@Test
	@DisplayName("nothing was lost: all 500 messages arrived")
	void every_message_arrives() {
		// Ordering assertions can pass while messages go missing — a group of three in the right
		// order still contains three of the five. This is the anti-vacuity guard for the test above.
		assertThat(received).hasSize(TOTAL_MESSAGES);
		assertThat(received.stream().map(Received::messageId).distinct().count())
				.as("no message was delivered twice")
				.isEqualTo(TOTAL_MESSAGES);
	}

	@Test
	@DisplayName("keying puts each order on a single partition (FR-026)")
	void each_order_lands_on_one_partition() {
		Map<UUID, Set<Integer>> partitionsPerOrder = new HashMap<>();
		for (Received record : received) {
			partitionsPerOrder.computeIfAbsent(record.sagaId(), key -> new HashSet<>()).add(record.partition());
		}

		// This is the mechanism the ordering guarantee rests on, asserted directly rather than
		// inferred. If an order were ever split across partitions, its messages would be ordered
		// within each partition and unordered between them — and the test above would start failing
		// intermittently, which is a far worse way to discover the same fact.
		assertThat(partitionsPerOrder).allSatisfy((sagaId, partitions) ->
				assertThat(partitions).as("partitions used by order %s", sagaId).hasSize(1));
	}

	@Test
	@DisplayName("orders spread across all three partitions (FR-027)")
	void orders_are_distributed_across_the_partitions() {
		Set<Integer> used = new HashSet<>();
		received.forEach(record -> used.add(record.partition()));

		// Without this, a single-partition topic would satisfy every other assertion in this class
		// perfectly — total ordering trivially preserves per-order ordering. That is exactly the
		// silent failure T029 disabled auto-creation to prevent, and it would leave the system with
		// no concurrency at all while every test stayed green.
		assertThat(used).containsExactlyInAnyOrder(0, 1, 2);
	}

	@Test
	@DisplayName("orders interleave in the log rather than arriving one order at a time")
	void different_orders_interleave() {
		// Counts how often consecutive consumed messages belong to different orders. If each order
		// were drained contiguously the count would be about ORDERS; genuine interleaving pushes it
		// far higher.
		//
		// HONEST LIMIT of this assertion, established by mutation: switching publish() from
		// round-robin to publishing each order contiguously does NOT make it fail, because eight
		// concurrent threads interleave their orders within a partition anyway. So this proves
		// orders are not processed one-at-a-time; it does not prove the round-robin schedule is
		// what achieves that. The round-robin is insurance against a machine where the threads
		// happen to serialise, not something this assertion measures.
		long switches = 0;
		for (int i = 1; i < received.size(); i++) {
			if (!received.get(i).sagaId().equals(received.get(i - 1).sagaId())) {
				switches++;
			}
		}

		assertThat(switches)
				.as("adjacent messages belonging to different orders")
				.isGreaterThan(ORDERS * 2L);
	}

	// --- setup ------------------------------------------------------------------------------------

	/**
	 * Creates the channel with three partitions, mirroring what {@code create-topics.sh} does for the
	 * real environment.
	 *
	 * <p>WHY it is created explicitly: the Testcontainers broker allows auto-creation, which would
	 * produce a ONE-partition topic on first reference. Every ordering assertion here would then pass
	 * for the wrong reason, since a single partition orders everything globally.
	 */
	private static void createTopic() throws Exception {
		Properties config = new Properties();
		config.put("bootstrap.servers", KAFKA.getBootstrapServers());
		try (Admin admin = Admin.create(config)) {
			admin.createTopics(List.of(new NewTopic(TOPIC, PARTITIONS, (short) 1))).all().get();
		}
	}

	/**
	 * Publishes {@value MESSAGES_PER_ORDER} messages for each of {@value ORDERS} orders from
	 * {@value PUBLISHER_THREADS} threads, and returns what was sent, per order, in production order.
	 *
	 * <p>Two properties of the schedule matter, and both are arranged deliberately rather than left
	 * to chance:
	 *
	 * <ul>
	 *   <li><b>Each order belongs to exactly one thread.</b> If two threads published messages for
	 *       the same order, "the order they were produced" would not be a defined thing, and the
	 *       assertion would be meaningless no matter what it observed.
	 *   <li><b>Each thread publishes round-robin</b> — message 1 of all its orders, then message 2 of
	 *       all its orders. So interleaving between orders holds by construction rather than by
	 *       luck, while the sequence within any single order is still strictly increasing. On this
	 *       machine eight concurrent threads interleave without help, so the round-robin is
	 *       insurance against a slower or busier machine where they might not, rather than the
	 *       thing that makes the interleaving assertion pass.
	 * </ul>
	 */
	private static Map<UUID, List<UUID>> publish() throws Exception {
		ObjectMapper mapper = EventJson.mapper();

		// The whole plan is built first, so "what was produced" is known independently of what the
		// threads happen to do with it.
		List<UUID> sagaIds = new ArrayList<>();
		Map<UUID, List<OrderCreated>> plan = new LinkedHashMap<>();
		Map<UUID, List<UUID>> expected = new LinkedHashMap<>();
		Instant start = Instant.parse("2026-01-01T00:00:00Z");

		for (int order = 0; order < ORDERS; order++) {
			UUID sagaId = UUID.randomUUID();
			sagaIds.add(sagaId);
			List<OrderCreated> messages = new ArrayList<>();
			List<UUID> ids = new ArrayList<>();
			for (int sequence = 0; sequence < MESSAGES_PER_ORDER; sequence++) {
				OrderCreated message = new OrderCreated(
						UUID.randomUUID(),
						sagaId,
						// Increasing within an order, so the intended sequence is legible in a dump of
						// the failure rather than only in this method.
						start.plusSeconds(sequence),
						1,
						// sagaId == orderId is a contract rule (Validation.requireSagaMatchesOrder).
						sagaId,
						UUID.randomUUID(),
						UUID.randomUUID(),
						// Matches the seatIds pattern in order-created.schema.json, ^[A-Z]+[0-9]+$.
						// "A-0" would not, and test data that contradicts the published contract is a
						// small lie that later gets copied into something that does validate (T052).
						List.of("A" + sequence),
						new BigDecimal("42.00"));
				messages.add(message);
				ids.add(message.messageId());
			}
			plan.put(sagaId, messages);
			expected.put(sagaId, ids);
		}

		Properties config = new Properties();
		config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
		config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
		config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
		// THE setting this test depends on. Without idempotence, a retried send can be written after
		// a later send that succeeded first, reordering messages inside a partition — the guarantee
		// would then hold only when nothing goes wrong, which is not a guarantee. It is the default
		// in modern clients; set explicitly because the correctness of everything below rests on it.
		config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
		config.put(ProducerConfig.ACKS_CONFIG, "all");

		ExecutorService publishers = Executors.newFixedThreadPool(PUBLISHER_THREADS);
		try (Producer<String, byte[]> producer = new KafkaProducer<>(config)) {
			for (int thread = 0; thread < PUBLISHER_THREADS; thread++) {
				// Every PUBLISHER_THREADS-th order, so each order has exactly one owning thread.
				final int offset = thread;
				List<UUID> mine = new ArrayList<>();
				for (int i = offset; i < sagaIds.size(); i += PUBLISHER_THREADS) {
					mine.add(sagaIds.get(i));
				}

				publishers.submit(() -> {
					for (int sequence = 0; sequence < MESSAGES_PER_ORDER; sequence++) {
						for (UUID sagaId : mine) {
							OrderCreated message = plan.get(sagaId).get(sequence);
							try {
								producer.send(new ProducerRecord<>(
										TOPIC,
										// The partition key (FR-026). Kafka hashes it to choose a partition,
										// so the same saga id always resolves to the same one.
										sagaId.toString(),
										mapper.writeValueAsBytes(message)));
							}
							catch (Exception e) {
								throw new IllegalStateException("publishing failed for " + sagaId, e);
							}
						}
					}
				});
			}

			publishers.shutdown();
			assertThat(publishers.awaitTermination(60, TimeUnit.SECONDS))
					.as("all publisher threads finished")
					.isTrue();
			// Sends are asynchronous; without this the producer could be closed with messages still
			// buffered, and the consumer would wait for records that were never actually sent.
			producer.flush();
		}

		return expected;
	}

	/** Reads the channel from the beginning until every published message has been seen. */
	private static List<Received> consume() throws Exception {
		Properties config = new Properties();
		config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
		config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
		config.put(ConsumerConfig.GROUP_ID_CONFIG, "ordering-guarantee-" + UUID.randomUUID());
		// The messages were published before this consumer existed, so the default of reading only
		// new records would return nothing at all.
		config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

		ObjectMapper mapper = EventJson.mapper();
		List<Received> collected = new ArrayList<>();

		try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(config)) {
			consumer.subscribe(List.of(TOPIC));

			// Bounded by wall clock as well as by count, so a lost message fails the size assertion
			// with a clear number rather than hanging the build until Maven is killed.
			Instant deadline = Instant.now().plusSeconds(60);
			while (collected.size() < TOTAL_MESSAGES && Instant.now().isBefore(deadline)) {
				ConsumerRecords<String, byte[]> batch = consumer.poll(Duration.ofMillis(500));
				for (ConsumerRecord<String, byte[]> record : batch) {
					OrderCreated message = mapper.readValue(record.value(), OrderCreated.class);
					collected.add(new Received(record.partition(), message.sagaId(), message.messageId()));
				}
			}
		}

		return collected;
	}
}
