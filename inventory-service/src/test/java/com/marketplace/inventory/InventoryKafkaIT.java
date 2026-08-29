package com.marketplace.inventory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.function.Predicate;

import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.TopicExistsException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.marketplace.events.OrderCreated;
import com.marketplace.events.SeatsRejected;
import com.marketplace.events.SeatsReserved;
import com.marketplace.events.Topics;

/**
 * Base class for integration tests that need PostgreSQL and Redis <em>and</em> Kafka — everything in
 * User Story 3, where this service actually consumes {@code order.created} and the outbox relay sends
 * a real message onto a real channel.
 *
 * <p>Extends {@link InventoryIT} rather than duplicating it, so every test needing a broker reuses the
 * same shared PostgreSQL and Redis containers the rest of this service's tests already start, and
 * only adds what {@link InventoryIT} deliberately left out.
 *
 * <p>WHY an independent {@link ObjectMapper} here, built fresh rather than reusing this service's own
 * {@code JacksonConfig}-produced bean: this class's whole job is proving what actually reaches the
 * wire and what an independent reader can make of it (SC-009) — the exact same reasoning
 * order-service's own {@code KafkaPostgresIT} gives for a raw {@code KafkaConsumer} instead of this
 * service's own consumer-side code. An {@code ObjectMapper} borrowed from the service under test would
 * happily deserialize whatever that same service just serialized, using the identical settings, which
 * proves the two agree with themselves — not that the message is genuinely readable by a party that
 * built its own understanding of the contract independently.
 */
public abstract class InventoryKafkaIT extends InventoryIT {

	/** Matches production (T044's {@code create-topics.sh}) so ordering-sensitive tests exercise the
	 * same partition count the real environment runs, not a coincidentally simpler shape. */
	protected static final int PARTITIONS = 3;

	// Pinned to the same image build step 1 uses everywhere else, so this needs no image this
	// machine hasn't already pulled, and cannot pass against a broker version the project does not run.
	protected static final KafkaContainer KAFKA =
			new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"))
					// Auto-creation would hand back a one-partition topic on first reference, which
					// would make every ordering assertion exercised through the channel pass for the
					// wrong reason. It also means a message aimed at a channel nobody provisioned
					// genuinely fails to send -- the real environment disables this for the identical
					// reason (T044), and UndecidableRequestIT (a later task) depends on that real
					// failure mode rather than a mock standing in for one.
					.withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

	/**
	 * Deserializes exactly as an independent reader would: {@code JavaTimeModule} for {@code Instant},
	 * ISO-8601 rather than epoch timestamps, and unknown fields tolerated -- the same three settings
	 * {@code JacksonConfig} configures for the service itself, arrived at independently here rather
	 * than imported from it, because the point is to prove the CONTRACT is readable, not to prove this
	 * service's own mapper agrees with its own mapper.
	 */
	protected static final ObjectMapper WIRE_MAPPER = new ObjectMapper()
			.registerModule(new JavaTimeModule())
			.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
			.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

	static {
		KAFKA.start();
		// Only the channels THIS service's own consumer and producer roles actually need:
		// order.created to consume, its DLT for messages this service cannot decide (FR-048), and
		// seats.reserved/seats.rejected to publish. This service never consumes its own outcome
		// messages, so their DLTs belong to whichever future service subscribes to them, not here.
		createTopicIfAbsent(Topics.ORDER_CREATED);
		createTopicIfAbsent(Topics.dlt(Topics.ORDER_CREATED));
		createTopicIfAbsent(Topics.SEATS_RESERVED);
		createTopicIfAbsent(Topics.SEATS_REJECTED);
	}

	@DynamicPropertySource
	static void kafkaProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
	}

	/**
	 * Provisions a channel exactly once, tolerating being asked again — several test classes share
	 * this one broker (the same singleton-container reasoning as {@link InventoryIT}).
	 */
	protected static void createTopicIfAbsent(String topic) {
		Properties config = new Properties();
		config.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
		try (Admin admin = Admin.create(config)) {
			admin.createTopics(List.of(new NewTopic(topic, PARTITIONS, (short) 1))).all().get();
		} catch (ExecutionException e) {
			if (!(e.getCause() instanceof TopicExistsException)) {
				throw new IllegalStateException("failed to create topic " + topic, e);
			}
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Publishes an {@code OrderCreated} directly onto the real channel using nothing this service
	 * wrote — the trigger half of an end-to-end test that then watches this service's own consumer
	 * react to it.
	 */
	protected static void publishOrderCreated(OrderCreated event) {
		produce(Topics.ORDER_CREATED, event.orderId().toString(), event);
	}

	private static void produce(String topic, String key, Object event) {
		Properties config = new Properties();
		config.put(org.apache.kafka.clients.producer.ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,
				KAFKA.getBootstrapServers());
		config.put(org.apache.kafka.clients.producer.ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,
				org.apache.kafka.common.serialization.StringSerializer.class);
		config.put(org.apache.kafka.clients.producer.ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,
				org.apache.kafka.common.serialization.StringSerializer.class);
		try (var producer = new org.apache.kafka.clients.producer.KafkaProducer<String, String>(config)) {
			String json = WIRE_MAPPER.writeValueAsString(event);
			producer.send(new org.apache.kafka.clients.producer.ProducerRecord<>(topic, key, json)).get();
		} catch (com.fasterxml.jackson.core.JsonProcessingException e) {
			throw new IllegalStateException("failed to serialize " + event, e);
		} catch (ExecutionException e) {
			throw new IllegalStateException("failed to publish to " + topic, e);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}

	/**
	 * Reads {@code seats.reserved} from the beginning until a message matching {@code sagaId} appears
	 * or {@code timeout} elapses, deserializing with {@link #WIRE_MAPPER} — an independent reader's
	 * view of what this service actually announced (SC-009), not this service's own in-memory
	 * decision.
	 */
	protected static SeatsReserved awaitSeatsReserved(UUID sagaId, Duration timeout) {
		return awaitOne(Topics.SEATS_RESERVED, SeatsReserved.class, sagaId, timeout);
	}

	/** The refusal counterpart to {@link #awaitSeatsReserved}. */
	protected static SeatsRejected awaitSeatsRejected(UUID sagaId, Duration timeout) {
		return awaitOne(Topics.SEATS_REJECTED, SeatsRejected.class, sagaId, timeout);
	}

	private static <T extends com.marketplace.events.SagaEvent> T awaitOne(
			String topic, Class<T> type, UUID sagaId, Duration timeout) {
		List<T> matches = new ArrayList<>();
		Predicate<List<ConsumerRecord<String, String>>> done = collected -> {
			matches.clear();
			for (ConsumerRecord<String, String> record : collected) {
				T parsed = deserialize(record.value(), type);
				if (parsed != null && parsed.sagaId().equals(sagaId)) {
					matches.add(parsed);
				}
			}
			return !matches.isEmpty();
		};
		poll(topic, timeout, done);
		if (matches.isEmpty()) {
			throw new IllegalStateException(
					"no " + type.getSimpleName() + " for sagaId=" + sagaId + " arrived on " + topic
							+ " within " + timeout);
		}
		return matches.get(0);
	}

	private static <T> T deserialize(String json, Class<T> type) {
		try {
			return WIRE_MAPPER.readValue(json, type);
		} catch (Exception e) {
			// A record on the same topic that does not parse as this type belongs to a different
			// test's message shape sharing this broker -- not this call's concern.
			return null;
		}
	}

	/**
	 * The general polling loop every helper above is built on: keeps reading {@code topic} from the
	 * beginning until {@code done} is satisfied by everything collected so far, or {@code timeout}
	 * elapses. A fresh, randomly named consumer group every call, so tests never see each other's
	 * committed offsets.
	 */
	protected static List<ConsumerRecord<String, String>> poll(
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

			// Bounded by wall clock as well as by the "done" predicate, so a message this service
			// never sends fails this call with a clear, incomplete result rather than hanging the
			// build until Maven is killed.
			Instant deadline = Instant.now().plus(timeout);
			while (!done.test(collected) && Instant.now().isBefore(deadline)) {
				ConsumerRecords<String, String> batch = consumer.poll(Duration.ofMillis(300));
				batch.forEach(collected::add);
			}
		}
		return collected;
	}
}
