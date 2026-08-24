package com.marketplace.orders;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.ExecutionException;

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

import com.marketplace.events.Topics;

import java.util.UUID;

/**
 * Base class for integration tests that need PostgreSQL <em>and</em> Kafka — everything in Phase 4,
 * where the relay actually sends messages.
 *
 * <p>Extends {@link PostgresIT} rather than duplicating it, so every Phase 4 test reuses the same
 * shared PostgreSQL container Phase 3's tests already start, and only adds what Phase 3 deliberately
 * left out: a broker.
 *
 * <p>WHY a raw {@link KafkaConsumer} rather than reading through this service's own configuration:
 * consuming with an independent client, using nothing this service wrote, is what proves the message
 * on the wire is correct — a channel name matching what {@code Topics} expects, a key equal to the
 * saga id, a payload another reader can parse — rather than merely proving one in-memory object equals
 * another (SC-008). Anything this service's own consumer-side code might get wrong would happily
 * "confirm" itself if used to check its own work.
 */
public abstract class KafkaPostgresIT extends PostgresIT {

	/** Matches production (`create-topics.sh`, T044) so ordering-sensitive tests exercise the same
	 * partition count the real environment runs, not a coincidentally-simpler shape. */
	protected static final int PARTITIONS = 3;

	// Pinned to the same image build step 1 uses everywhere else (T029), so this needs no image this
	// machine hasn't already pulled, and cannot pass against a broker version the project does not run.
	protected static final KafkaContainer KAFKA =
			new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"))
					// Auto-creation would hand back a one-partition topic on first reference, which
					// would make every ordering assertion in Phase 4 pass for the wrong reason (T029
					// disables this in the real environment for the identical reason). It also means a
					// message aimed at a channel nobody provisioned genuinely fails to send — which is
					// exactly the send-failure scenario OutboxRelayIT and OutboxOrderingIT need, gotten
					// from real broker behaviour rather than a mock standing in for one.
					.withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false");

	static {
		KAFKA.start();
		createTopicIfAbsent(Topics.ORDER_CREATED);
	}

	@DynamicPropertySource
	static void kafkaProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
	}

	/**
	 * Provisions a channel exactly once, tolerating being asked again — several Phase 4 test classes
	 * share this one broker (the same singleton-container reasoning as {@link PostgresIT}), and each
	 * wants {@code order.created} to already exist.
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
	 * Reads at least {@code minCount} records from {@code topic}, from the beginning, waiting up to
	 * {@code timeout}. A fresh, randomly named consumer group every call, so tests never see each
	 * other's committed offsets.
	 */
	protected static List<ConsumerRecord<String, String>> consume(String topic, int minCount, Duration timeout) {
		Properties config = new Properties();
		config.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
		config.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		config.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
		config.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
		config.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

		List<ConsumerRecord<String, String>> collected = new ArrayList<>();
		try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(config)) {
			consumer.subscribe(List.of(topic));

			// Bounded by wall clock as well as by count, so a message the relay never sends fails this
			// call with a clear "only got 3 of 5" rather than hanging the build until Maven is killed.
			Instant deadline = Instant.now().plus(timeout);
			while (collected.size() < minCount && Instant.now().isBefore(deadline)) {
				ConsumerRecords<String, String> batch = consumer.poll(Duration.ofMillis(300));
				batch.forEach(collected::add);
			}
		}
		return collected;
	}
}
