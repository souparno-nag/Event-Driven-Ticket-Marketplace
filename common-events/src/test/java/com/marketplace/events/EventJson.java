package com.marketplace.events;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * Builds the {@link ObjectMapper} the contract tests serialize with (R3).
 *
 * <p>WHY this lives under {@code src/test/java} rather than in the module's main sources, where
 * tasks.md nominally places it: {@code ObjectMapper} comes from {@code jackson-databind}, and T006
 * declared that dependency at test scope on purpose. Only {@code jackson-annotations} is at compile
 * scope, so a class in main sources referencing {@code ObjectMapper} would not compile without
 * promoting databind — which would push a serializer onto every service that depends on this module
 * and take away its ability to configure serialization its own way. T013 anticipates exactly this
 * and permits test scope so long as production code never serializes here, which it does not:
 * services get their mapper from Spring Boot's autoconfiguration.
 *
 * <p>TRADEOFF: the four settings below are therefore not automatically shared with the services
 * built in later steps, which is a genuine drift risk. It is small and asymmetric. Spring Boot
 * already registers {@code JavaTimeModule}, already disables {@code WRITE_DATES_AS_TIMESTAMPS}, and
 * already disables {@code FAIL_ON_UNKNOWN_PROPERTIES}, so three of the four match by default. Only
 * {@code WRITE_BIGDECIMAL_AS_PLAIN} has to be set deliberately per service, via
 * {@code spring.jackson.serialization.write-bigdecimal-as-plain: true}. The alternative — exporting
 * a mapper from the contract module — trades that one line of configuration for a hard dependency
 * every consumer carries whether it wants it or not.
 */
public final class EventJson {

	/**
	 * A freshly configured mapper. Every setting here exists because Jackson's default is wrong for
	 * these contracts.
	 *
	 * <p>WHY a new instance per call rather than a shared static one: {@code ObjectMapper} is
	 * thread-safe once configured but is <em>not</em> immutable, so a single shared instance lets any
	 * caller reconfigure serialization for everyone else. Construction is cheap relative to a test,
	 * and an isolated mapper keeps one test from changing another's meaning.
	 */
	public static ObjectMapper mapper() {
		return JsonMapper.builder()

				// Without this, Jackson has no idea what an Instant is and falls back to reflecting
				// over its internal fields — emitting {"seconds":...,"nanos":...} rather than a
				// timestamp, which then fails to read back. The module teaches it java.time.
				.addModule(new JavaTimeModule())

				// The module's own default is still a number (epoch seconds with a nanosecond
				// fraction). Disabling this switches Instant to an ISO-8601 string:
				// "2026-08-22T09:15:30.123456789Z". WHY that is worth the extra bytes: a message
				// read straight off a Kafka channel stays legible to a human, which is most of the
				// value of a JSON wire format, and full nanosecond precision survives the round trip
				// so the FR-006 equality assertion holds.
				.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)

				// FR-007 directly. By default an unrecognised field is an exception, which would mean
				// a consumer on an older build crashing on a message from a newly deployed producer
				// that added a field. Ignoring unknown fields is what makes an additive change
				// backward compatible, and during any rolling deployment both builds are running at
				// once.
				.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

				// Money is BigDecimal, and Jackson may write a BigDecimal in scientific notation:
				// 1E+2 rather than 100.00. Both parse back to an equal number, but the text differs,
				// and the payment rule this project simulates keys off the last digit of the amount.
				// Writing plain keeps the serialized form predictable for that rule and for anyone
				// eyeballing a message.
				.enable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN)

				.build();
	}

	// CAUTION (R3): Instant round-trips at nanosecond precision through JSON, but PostgreSQL
	// timestamptz stores microseconds. The truncation is invisible until a value crosses the
	// database in step 2, where an equality check against a stored timestamp will fail for reasons
	// that look nothing like rounding. Comparisons involving stored times should truncate to
	// microseconds; the tests here compare pure serialization, so they do not.

	// Not instantiable: a factory, not a thing with state.
	private EventJson() {
	}
}
