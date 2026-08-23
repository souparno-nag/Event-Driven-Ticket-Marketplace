package com.marketplace.orders.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

/**
 * JSON settings for this service.
 *
 * <p>WHY this class exists at all, when the contract module already defines the message shapes: the
 * contract module ships {@code jackson-annotations} only — annotations, no serializer — deliberately,
 * so that it can describe the shape of a message without dictating how any service writes it. The
 * consequence is that each service configures its own serialization, and each service can therefore
 * get it wrong on its own. This is where this one gets it right.
 *
 * <p>The same mapper serves both jobs on purpose: HTTP request and response bodies, and the outbox
 * payload. Two differently configured mappers in one service is a bug waiting for the day an amount
 * looks correct in an API response and wrong on the wire.
 */
@Configuration
public class JacksonConfig {

	/**
	 * Built from Spring Boot's pre-configured builder rather than from {@code new ObjectMapper()}.
	 *
	 * <p>WHY that matters: the builder already carries Boot's defaults and every Jackson module found
	 * on the classpath, including {@code JavaTimeModule}. Starting from a bare mapper would silently
	 * drop all of that, and the first symptom would be an {@code Instant} serializing as a nested
	 * object of internal fields rather than a timestamp — which reads like a bug in the message
	 * record rather than a missing module.
	 *
	 * <p>Declaring this bean makes Boot's own auto-configured mapper back off, so this is the single
	 * {@code ObjectMapper} in the application context.
	 */
	@Bean
	public ObjectMapper objectMapper(Jackson2ObjectMapperBuilder builder) {
		return builder.build()

				// THE TRAP THIS SERVICE WAS WARNED ABOUT. Jackson may write a BigDecimal in
				// scientific notation — 1E+2 rather than 100.00. Both parse back to an equal number,
				// so nothing here would notice; the failure surfaces in a CONSUMER, whose schema
				// expects a plain decimal, and by then the message is already on the channel.
				//
				// This is exactly the kind of default that is only wrong once you cross a service
				// boundary, which is why it is set explicitly rather than assumed.
				.enable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN)

				// Write an Instant as an ISO-8601 string rather than as epoch seconds with a
				// nanosecond fraction. Boot already disables this; it is repeated here because the
				// wire format of a frozen contract should not depend on a framework default that a
				// future version could reasonably change.
				//
				// CAUTION: Instant round-trips through JSON at nanosecond precision, but PostgreSQL
				// timestamptz stores microseconds. A value that has been through the database is
				// therefore not equal to the one that went in. Comparisons involving stored times
				// must truncate to microseconds — the truncation is invisible until it is not.
				.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

				// An unrecognised field must be ignored rather than throwing. During any rolling
				// deployment an older consumer meets messages from a newer producer, and a producer
				// adding a field is supposed to be a backward-compatible change. Failing on unknown
				// fields is what would make it a breaking one.
				.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
	}

	// TRADEOFF: all three settings are also expressible as spring.jackson.* properties in
	// application.yml, which would be three lines instead of this class. They are code here because
	// each is a correctness requirement of the message contracts rather than an environment setting,
	// and application.yml is the file people edit when changing environments. A value that must
	// never differ between environments does not belong in the file whose purpose is to differ.
}
