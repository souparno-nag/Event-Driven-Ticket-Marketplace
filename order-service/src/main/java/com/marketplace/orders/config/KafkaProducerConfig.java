package com.marketplace.orders.config;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;

/**
 * The Kafka producer the outbox relay publishes with.
 *
 * <p>Built from Spring Boot's own {@link KafkaProperties} rather than a bare hand-written map. WHY
 * that matters beyond tidiness: {@code buildProducerProperties()} resolves {@code
 * spring.kafka.bootstrap-servers} from wherever it is actually configured — {@code application.yml}
 * in production, or a Testcontainers broker's address injected via {@code @DynamicPropertySource} in
 * every Phase 4 test. A hand-rolled map naming only the settings this class cares about would silently
 * default the broker address to {@code localhost:9092} and ignore that override entirely — the tests
 * would then try to reach a broker that was never started, for a reason that looks nothing like a
 * missing bootstrap-servers property once it fails.
 *
 * <p>Both the key and the value are {@link StringSerializer}, not a JSON serializer of any kind. WHY
 * that matters more than it looks: the message was already serialized once, when its outbox row was
 * written ({@code OutboxWriter}, T081) — that is the whole point of storing the payload as text
 * rather than as a live object. A serializer here would mean turning a stored String back into an
 * object just to immediately turn it back into a String again, and the round trip is where the
 * {@code 1E+2} money bug {@code WRITE_BIGDECIMAL_AS_PLAIN} (T070) was written to prevent could
 * silently reappear. The relay sends exactly the bytes that were stored — {@code KafkaTemplate<String,
 * String>} is what makes "send bytes, not an object" the only thing this producer is even able to do.
 */
@Configuration
public class KafkaProducerConfig {

	@Bean
	public ProducerFactory<String, String> outboxProducerFactory(KafkaProperties kafkaProperties) {
		Map<String, Object> config = new HashMap<>(kafkaProperties.buildProducerProperties(null));

		config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
		config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);

		// "Marked published" is only allowed to mean "the broker has it" (R3) — acks=all is what
		// makes an acknowledgement mean the message reached every in-sync replica, not merely the
		// leader that could still lose it before replicating.
		config.put(ProducerConfig.ACKS_CONFIG, "all");

		// Idempotence stops Kafka's OWN internal retries from reordering or duplicating a message
		// within a partition. This project's own retry logic (parking after max-attempts) is a
		// separate, higher-level concern; this setting is what keeps the low-level retries the Kafka
		// client does on its own from corrupting per-order ordering while it does them.
		config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);

		// The idempotent producer's own upper bound on in-flight requests without breaking ordering
		// (Kafka enforces this itself above 5; set explicitly so the value is a documented decision
		// here rather than a client default someone has to look up).
		config.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 5);

		return new DefaultKafkaProducerFactory<>(config);
	}

	@Bean
	public KafkaTemplate<String, String> kafkaTemplate(ProducerFactory<String, String> outboxProducerFactory) {
		return new KafkaTemplate<>(outboxProducerFactory);
	}
}
