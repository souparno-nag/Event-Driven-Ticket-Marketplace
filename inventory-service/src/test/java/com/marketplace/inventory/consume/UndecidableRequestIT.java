package com.marketplace.inventory.consume;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.Predicate;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.marketplace.events.OrderCreated;
import com.marketplace.events.Topics;

/**
 * SC-018/SC-019: none of this service's three frozen refusal causes means "the decision could not be
 * made" (contracts/inventory-consumer.md) — a store outage must produce NO outcome message at all,
 * must self-heal with no manual step once the store recovers, and a message that genuinely can never
 * be decided must still reach the dead-letter channel within a bounded number of attempts rather than
 * stalling its partition forever.
 *
 * <p>WHY this class does not extend {@link com.marketplace.inventory.InventoryIT} or
 * {@link com.marketplace.inventory.InventoryKafkaIT}, unlike every other integration test in this
 * service: those two share one PostgreSQL and one Redis container across the ENTIRE test JVM
 * (deliberately, for the reasons their own Javadoc gives). This class's whole job is making a store
 * briefly unreachable and then reachable again -- doing that to a container every other test class
 * depends on would break every test that happens to run afterward in the same fork. This class brings
 * up its own private PostgreSQL, Redis, and Kafka, used by nothing else, so it is free to disrupt its
 * own Redis without consequence to anything else.
 *
 * <p>WHY Redis is PAUSED rather than stopped: a stopped and restarted Testcontainers container is not
 * guaranteed to come back on the same mapped port, and this test's Spring context already baked the
 * original port into its connection pool at startup -- restarting on a new port would leave that pool
 * unable to ever reconnect, which would test nothing about recovery and everything about a test
 * artifact. Pausing the container's own process (the same {@code docker pause} quickstart.md's own S7
 * scenario approximates with {@code docker stop}) freezes it in place with its network identity fully
 * intact: connections simply hang until it is unpaused, which is a more faithful reproduction of "the
 * store is unreachable" than tearing the container down would be anyway.
 *
 * <p>WHY {@code spring.kafka.listener.auto-startup} is overridden to {@code true} here, unlike every
 * other test: the real, production-shaped {@code false} default exists specifically so
 * {@code SeatLockRebuilder} (T179) controls when consumption begins (FR-015) -- a concern
 * {@link com.marketplace.inventory.startup.SeatLockRebuildIT} owns exclusively. This class is testing
 * a completely different guarantee (what happens to a message once consumption is ALREADY underway)
 * and overriding the gate here keeps the two concerns from being tangled into one test.
 *
 * <p>Passes now that {@code OrderCreatedListener} (T178) and {@code IdempotencyGuard} (T174) both
 * exist. Every assertion below that checks a channel stays silent (or carries only what THIS test's
 * own request produced) is filtered to this test's own message key before asserting — this topic is
 * shared with sibling test methods in this same class, and an unfiltered emptiness check was found,
 * directly rather than assumed, to fail on an unrelated sibling's own leftover message still sitting
 * on the shared topic, not on anything this test's own request actually did wrong.
 */
@SpringBootTest(classes = com.marketplace.inventory.InventoryServiceApplication.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class UndecidableRequestIT {

	private static final PostgreSQLContainer<?> POSTGRES =
			new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

	private static final GenericContainer<?> REDIS =
			new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
					.withExposedPorts(6379)
					.waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));

	private static final KafkaContainer KAFKA =
			new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"))
					.withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

	private static final ObjectMapper WIRE_MAPPER = new ObjectMapper()
			.registerModule(new JavaTimeModule())
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

	static {
		POSTGRES.start();
		REDIS.start();
		KAFKA.start();
		createTopicIfAbsent(Topics.ORDER_CREATED);
		createTopicIfAbsent(Topics.dlt(Topics.ORDER_CREATED));
		createTopicIfAbsent(Topics.SEATS_RESERVED);
		createTopicIfAbsent(Topics.SEATS_REJECTED);
	}

	@AfterAll
	static void ensureRedisIsRunningForContextTeardown() {
		// A test left Redis paused, this container never runs again, and Ryuk still needs to be able
		// to stop it cleanly on JVM exit -- unpausing here is cheap insurance against a paused
		// container behaving strangely during cleanup, regardless of which test ran last.
		if (isPaused()) {
			REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
		}
	}

	@DynamicPropertySource
	static void containerProperties(DynamicPropertyRegistry registry) {
		String url = POSTGRES.getJdbcUrl() + (POSTGRES.getJdbcUrl().contains("?") ? "&" : "?") + "currentSchema=inventory";
		registry.add("spring.datasource.url", () -> url);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
		registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
		registry.add("spring.kafka.listener.auto-startup", () -> "true");
	}

	private static void createTopicIfAbsent(String topic) {
		Properties config = new Properties();
		config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
		try (Admin admin = Admin.create(config)) {
			admin.createTopics(List.of(new NewTopic(topic, 3, (short) 1))).all().get();
		} catch (java.util.concurrent.ExecutionException e) {
			if (!(e.getCause() instanceof TopicExistsException)) {
				throw new IllegalStateException("failed to create topic " + topic, e);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	@Autowired
	JdbcTemplate jdbcTemplate;

	private static boolean isPaused() {
		return Boolean.TRUE.equals(REDIS.getDockerClient()
				.inspectContainerCmd(REDIS.getContainerId()).exec().getState().getPaused());
	}

	private static void pauseRedis() {
		REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();
	}

	private static void unpauseRedis() {
		REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
	}

	private com.marketplace.inventory.SeatingPlanFixture.ProvisionedShow provisionShow(String namePrefix, int seatCount) {
		return com.marketplace.inventory.SeatingPlanFixture.provisionShow(jdbcTemplate, namePrefix, seatCount);
	}

	private void publishOrderCreated(OrderCreated event) {
		produce(Topics.ORDER_CREATED, event.orderId().toString(), toJson(event));
	}

	private static String toJson(OrderCreated event) {
		try {
			return WIRE_MAPPER.writeValueAsString(event);
		} catch (Exception e) {
			throw new IllegalStateException(e);
		}
	}

	private static void produce(String topic, String key, String value) {
		Properties config = new Properties();
		config.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
		config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		try (var producer = new KafkaProducer<String, String>(config)) {
			producer.send(new ProducerRecord<>(topic, key, value)).get();
		} catch (Exception e) {
			throw new IllegalStateException("failed to publish to " + topic, e);
		}
	}

	private static List<ConsumerRecord<String, String>> poll(
			String topic, Duration timeout, Predicate<List<ConsumerRecord<String, String>>> done) {
		Properties config = new Properties();
		config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
		config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		config.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
		config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

		List<ConsumerRecord<String, String>> collected = new ArrayList<>();
		try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
			consumer.subscribe(List.of(topic));
			Instant deadline = Instant.now().plus(timeout);
			while (!done.test(collected) && Instant.now().isBefore(deadline)) {
				ConsumerRecords<String, String> batch = consumer.poll(Duration.ofMillis(300));
				batch.forEach(collected::add);
			}
		}
		return collected;
	}

	private static OrderCreated newOrder(UUID showId, List<String> seatIds) {
		UUID orderId = UUID.randomUUID();
		return new OrderCreated(UUID.randomUUID(), orderId, Instant.now(), 1,
				orderId, UUID.randomUUID(), showId, seatIds, new BigDecimal("10.00"));
	}

	@Test
	void noFalseRefusalWhileDown() throws InterruptedException {
		var show = provisionShow("Undecidable-NoFalseRefusal", 1);
		OrderCreated event = newOrder(show.showId(), List.of(show.seatLabels().get(0)));

		pauseRedis();
		try {
			publishOrderCreated(event);

			// Neither outcome channel may receive anything while the store neither this service's own
			// hold attempt nor its lapsed-reservation retirement can reach is unavailable -- silence on
			// BOTH channels is the only answer that states nothing false about seats that were never
			// actually evaluated.
			//
			// WHY these assertions filter to THIS event's own key rather than asserting the whole
			// polled list is empty -- found necessary, not assumed: poll(...) subscribes from the
			// beginning of a topic every call, and this class's own sibling tests
			// (recoversWithoutReplay, dlttedAtAttemptLimit) publish to these SAME two channels. A
			// straight isEmpty() assertion here was failing on a PREVIOUS test's own already-published,
			// entirely unrelated message still sitting on the shared topic -- confirmed directly by
			// printing the found record's own key and seeing it belonged to a different order entirely,
			// not this test's own. Filtering to this event's key before asserting is what makes the
			// assertion mean "nothing was ever produced for THIS request", the actual claim this test
			// makes, rather than "the topic has never carried any message at all", which no test sharing
			// a topic with siblings can honestly assert.
			var reserved = poll(Topics.SEATS_RESERVED, Duration.ofSeconds(8),
					collected -> collected.stream().anyMatch(r -> r.key().equals(event.orderId().toString())));
			var rejected = poll(Topics.SEATS_REJECTED, Duration.ofSeconds(2),
					collected -> collected.stream().anyMatch(r -> r.key().equals(event.orderId().toString())));

			assertThat(reserved).filteredOn(r -> r.key().equals(event.orderId().toString()))
					.as("no false grant while the store is down").isEmpty();
			assertThat(rejected).filteredOn(r -> r.key().equals(event.orderId().toString()))
					.as("no false refusal while the store is down").isEmpty();
		} finally {
			unpauseRedis();
		}
	}

	@Test
	void recoversWithoutReplay() throws InterruptedException {
		var show = provisionShow("Undecidable-Recovers", 1);
		OrderCreated event = newOrder(show.showId(), List.of(show.seatLabels().get(0)));

		pauseRedis();
		publishOrderCreated(event);
		// A brief window with the store down and nothing decided yet -- confirming there is genuinely
		// something stuck to recover, not asserting recovery of a request that already succeeded.
		Thread.sleep(1000);
		unpauseRedis();

		// No manual step of any kind beyond unpausing the store: the SAME redeliveries Kafka was
		// already scheduled to make are what decide this request once the store answers again.
		//
		// Filtered to this event's own key before asserting non-emptiness, for the identical reason
		// noFalseRefusalWhileDown's own assertions are filtered: poll(...) reads this shared topic
		// from the beginning, so an unrelated sibling test's own earlier message would make a plain
		// isNotEmpty() check pass even if THIS request was never actually decided at all.
		var reserved = poll(Topics.SEATS_RESERVED, Duration.ofSeconds(15),
				collected -> collected.stream().anyMatch(r -> r.key().equals(event.orderId().toString())));
		assertThat(reserved).filteredOn(r -> r.key().equals(event.orderId().toString()))
				.as("the request is decided once the store recovers, with no replay").isNotEmpty();
	}

	@Test
	void dlttedAtAttemptLimit() throws InterruptedException {
		var show = provisionShow("Undecidable-DltAtLimit", 1);
		OrderCreated event = newOrder(show.showId(), List.of(show.seatLabels().get(0)));

		pauseRedis();
		try {
			publishOrderCreated(event);

			// inventory.consumer.max-attempts=4 at inventory.consumer.backoff-ms=500 doubling is a few
			// seconds of total backoff -- 20s is a generous margin, not a tight budget being trusted
			// to just barely work.
			// Filtered to this event's own key before asserting non-emptiness: a timed-out poll (this
			// request never actually reaching the DLT) could otherwise still return a non-empty list
			// thanks to an unrelated sibling test's own earlier DLT message on this same shared topic,
			// letting a genuine failure here pass silently.
			var dltMessages = poll(Topics.dlt(Topics.ORDER_CREATED), Duration.ofSeconds(20),
					collected -> collected.stream().anyMatch(r -> r.key().equals(event.orderId().toString())));
			assertThat(dltMessages).filteredOn(r -> r.key().equals(event.orderId().toString()))
					.as("an undecidable message reaches the DLT within its bounded attempt limit")
					.isNotEmpty();
		} finally {
			unpauseRedis();
		}
	}

	@Test
	void unknownVersionGoesToDlt() {
		// Built as raw JSON rather than an OrderCreated instance: the record's own compact
		// constructor validates schemaVersion and would refuse to let this object exist in the first
		// place (Validation.requireSchemaVersion) -- exactly correct for application code, and exactly
		// why THIS test has to bypass it to construct the one shape a real producer running a future,
		// incompatible build could actually put on the wire.
		UUID orderId = UUID.randomUUID();
		String malformed = """
				{"messageId":"%s","sagaId":"%s","occurredAt":"%s","schemaVersion":99,\
				"orderId":"%s","userId":"%s","showId":"%s","seatIds":["A1"],"amount":10.00}
				""".formatted(UUID.randomUUID(), orderId, Instant.now(), orderId, UUID.randomUUID(), UUID.randomUUID())
				.strip();

		produce(Topics.ORDER_CREATED, orderId.toString(), malformed);

		// An unrecognised schemaVersion is classified NON-retryable (contracts/inventory-consumer.md) --
		// it must reach the DLT immediately, well inside the multi-second window a retried failure
		// would need, not merely eventually.
		// Filtered to this event's own key for the identical reason every other assertion in this
		// class now is: this topic is shared across sibling tests, so an unfiltered non-emptiness
		// check could pass on an unrelated leftover message even if THIS message never actually
		// reached the DLT at all.
		var dltMessages = poll(Topics.dlt(Topics.ORDER_CREATED), Duration.ofSeconds(5),
				collected -> collected.stream().anyMatch(r -> r.key().equals(orderId.toString())));
		assertThat(dltMessages).filteredOn(r -> r.key().equals(orderId.toString()))
				.as("an unrecognised schemaVersion is dead-lettered immediately").isNotEmpty();
	}
}
