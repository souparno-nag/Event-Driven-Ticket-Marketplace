package com.marketplace.inventory.config;

import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.serializer.ErrorHandlingDeserializer;
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;
import org.springframework.util.backoff.ExponentialBackOff;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketplace.events.OrderCreated;
import com.marketplace.events.Topics;
import com.marketplace.inventory.consume.UnknownSchemaVersionException;

/**
 * Everything that decides what happens to an {@code order.created} message this service cannot
 * process cleanly on the first attempt — deserialization, retry policy, and the dead-letter route —
 * per {@code contracts/inventory-consumer.md} and research.md R9.
 *
 * <p>None of this lives in {@code application.yml}, unlike most of this service's Kafka configuration.
 * The same reasoning order-service's own producer specifics rest on applies here: which failures are
 * retryable, how the backoff behaves, and where a poison message ends up are correctness decisions
 * this saga depends on, not environment settings a file whose purpose is to be freely edited should be
 * trusted with.
 */
@Configuration
public class KafkaConsumerConfig {

	/**
	 * The typed consumer factory {@code OrderCreatedListener} (T178) is built against.
	 *
	 * <p>{@link ErrorHandlingDeserializer} wraps the real {@link JsonDeserializer} for both key and
	 * value. WHY this matters more than it looks: without it, a message whose bytes are not valid
	 * JSON at all — the poison-pill case FR-003 names — throws DURING deserialization, before Spring
	 * Kafka's listener machinery has a {@code ConsumerRecord} to hand to any error handler at all.
	 * {@code ErrorHandlingDeserializer} catches that failure itself and hands the container a special
	 * marker the {@link DefaultErrorHandler} below recognises and routes to the dead letter channel
	 * immediately, with the ORIGINAL raw bytes preserved for inspection — exactly what
	 * {@code UndecidableRequestIT#unknownVersionGoesToDlt} and its sibling for a genuinely malformed
	 * payload both depend on.
	 *
	 * <p>{@code ignoreTypeHeaders()}: this service's own outbox ({@code KafkaProducerConfig}) and
	 * order-service's outbox both publish plain JSON text via a bare {@code StringSerializer} — no
	 * Spring type-id header is ever added on the wire. Looking for one anyway would find nothing and
	 * fall back to the target type regardless, but stating the true wire shape explicitly here is
	 * clearer than relying on a fallback path to happen to produce the right answer.
	 */
	@Bean
	public ConsumerFactory<String, OrderCreated> orderCreatedConsumerFactory(
			KafkaProperties kafkaProperties, ObjectMapper objectMapper) {
		Map<String, Object> props = kafkaProperties.buildConsumerProperties(null);

		JsonDeserializer<OrderCreated> valueDeserializer =
				new JsonDeserializer<>(OrderCreated.class, objectMapper).ignoreTypeHeaders();
		valueDeserializer.addTrustedPackages("com.marketplace.events");

		return new DefaultKafkaConsumerFactory<>(props,
				new ErrorHandlingDeserializer<>(new StringDeserializer()),
				new ErrorHandlingDeserializer<>(valueDeserializer));
	}

	/**
	 * The byte[]-valued dead-letter route: the destination a deserialization failure is published
	 * through. {@link DeadLetterPublishingRecoverer} recovers a deserialization failure using the
	 * ORIGINAL raw bytes it could not parse, never the (nonexistent) typed object — a template whose
	 * value serializer expects an {@code OrderCreated} would throw trying to serialize raw bytes as
	 * one, which is why this dead-letter route needs a template of its own rather than reusing this
	 * service's existing {@code KafkaTemplate<String, String>} outbox template.
	 */
	@Bean
	public KafkaTemplate<String, byte[]> rawBytesDeadLetterTemplate(KafkaProperties kafkaProperties) {
		Map<String, Object> props = new HashMap<>(kafkaProperties.buildProducerProperties(null));
		ProducerFactory<String, byte[]> factory = new DefaultKafkaProducerFactory<>(
				props, new StringSerializer(), new ByteArraySerializer());
		return new KafkaTemplate<>(factory);
	}

	/**
	 * The {@code OrderCreated}-valued dead-letter route: the destination a failure discovered AFTER
	 * successful deserialization is published through — an unrecognised {@code schemaVersion}
	 * ({@link UnknownSchemaVersionException}), or an infrastructure failure that exhausted every
	 * retry. In both cases the value {@link DeadLetterPublishingRecoverer} is holding is a fully
	 * materialised {@code OrderCreated}, not bytes, so this route needs a value serializer that can
	 * actually write one — {@link JsonSerializer}, built from the SAME {@link ObjectMapper} bean this
	 * service uses everywhere else, so a message that reaches the dead-letter channel this way is
	 * byte-for-byte what this service would have published had the decision succeeded.
	 */
	@Bean
	public KafkaTemplate<String, OrderCreated> orderCreatedDeadLetterTemplate(
			KafkaProperties kafkaProperties, ObjectMapper objectMapper) {
		Map<String, Object> props = new HashMap<>(kafkaProperties.buildProducerProperties(null));
		ProducerFactory<String, OrderCreated> factory = new DefaultKafkaProducerFactory<>(
				props, new StringSerializer(), new JsonSerializer<>(objectMapper));
		return new KafkaTemplate<>(factory);
	}

	/**
	 * Routes a recovered record to {@code Topics.dlt(originalTopic)} rather than
	 * {@link DeadLetterPublishingRecoverer}'s own {@code -dlt}-suffixed default.
	 *
	 * <p>THE GOTCHA THIS EXISTS TO AVOID, restated because it is easy to reintroduce by accident: the
	 * default suffix produces {@code order.created-dlt}. Step 1's provisioning script created
	 * {@code order.created.DLT} — {@code Topics.dlt()}'s own contract — and with
	 * {@code auto.create.topics.enable=false} in the real environment, a recovery aimed at the wrong
	 * name does not fall back to creating it; it fails, and the message this recovery was trying to
	 * save is lost instead of merely misrouted (research.md R9).
	 *
	 * <p>{@code -1} as the target partition lets the producer choose one by the record's own key hash,
	 * matching ordinary publication elsewhere in this service rather than forcing every dead-lettered
	 * message onto the source partition number, which the destination topic may not even have.
	 */
	@Bean
	public DeadLetterPublishingRecoverer deadLetterPublishingRecoverer(
			KafkaTemplate<String, byte[]> rawBytesDeadLetterTemplate,
			KafkaTemplate<String, OrderCreated> orderCreatedDeadLetterTemplate) {
		Map<Class<?>, KafkaOperations<?, ?>> templatesByValueType = Map.of(
				byte[].class, rawBytesDeadLetterTemplate,
				OrderCreated.class, orderCreatedDeadLetterTemplate);

		return new DeadLetterPublishingRecoverer(templatesByValueType,
				(record, exception) -> new TopicPartition(Topics.dlt(record.topic()), -1));
	}

	/**
	 * The retry policy and failure classification research.md R9 decides: bounded exponential
	 * backoff, then the dead letter channel — with exactly two exception types excused from ever being
	 * retried at all, because retrying them a bounded number of times and retrying them zero times
	 * produce the identical outcome, just slower.
	 *
	 * <p>{@code max-attempts} counts the ORIGINAL delivery as attempt one, matching
	 * {@link ExponentialBackOff#setMaxAttempts(int)}'s own semantics — {@code inventory.consumer.max-
	 * attempts: 4} means one original delivery plus three redeliveries, not four redeliveries on top
	 * of the first.
	 */
	@Bean
	public DefaultErrorHandler kafkaErrorHandler(
			DeadLetterPublishingRecoverer deadLetterPublishingRecoverer,
			@Value("${inventory.consumer.max-attempts:4}") int maxAttempts,
			@Value("${inventory.consumer.backoff-ms:500}") long initialBackoffMs) {
		ExponentialBackOff backOff = new ExponentialBackOff(initialBackoffMs, 2.0);
		backOff.setMaxAttempts(maxAttempts);

		DefaultErrorHandler errorHandler = new DefaultErrorHandler(deadLetterPublishingRecoverer, backOff);

		// A schema version this service does not understand will never be understood no matter how
		// many times it is redelivered -- retrying it only delays the dead-letter outcome that is
		// already certain, holding up this order's whole partition for no benefit (FR-003).
		errorHandler.addNotRetryableExceptions(UnknownSchemaVersionException.class);

		return errorHandler;
	}

	/** The container factory {@code @KafkaListener}-annotated methods (T178) are built against. */
	@Bean
	public ConcurrentKafkaListenerContainerFactory<String, OrderCreated> kafkaListenerContainerFactory(
			ConsumerFactory<String, OrderCreated> orderCreatedConsumerFactory,
			DefaultErrorHandler kafkaErrorHandler) {
		ConcurrentKafkaListenerContainerFactory<String, OrderCreated> factory =
				new ConcurrentKafkaListenerContainerFactory<>();
		factory.setConsumerFactory(orderCreatedConsumerFactory);
		factory.setCommonErrorHandler(kafkaErrorHandler);
		return factory;
	}
}
