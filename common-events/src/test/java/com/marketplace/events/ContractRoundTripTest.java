package com.marketplace.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Every message survives a trip through JSON and comes back equal (FR-006, SC-003, SC-006).
 *
 * <p>WHY this is the first test written: it is the only property the entire system depends on
 * without exception. A message that deserializes into something subtly different from what was
 * published corrupts a saga in a way no consumer can detect, because the consumer never sees the
 * original. Everything else in this module is a refinement of this one guarantee.
 */
class ContractRoundTripTest {

	// Fixed rather than random. WHY: a failure should be reproducible from the source alone, and a
	// randomly generated id turns "this test failed" into "this test failed that time".
	private static final UUID ORDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID SHOW_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
	private static final UUID RESERVATION_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
	private static final UUID PAYMENT_ID = UUID.fromString("55555555-5555-5555-5555-555555555555");
	private static final UUID MESSAGE_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");

	// Nine fractional digits on purpose — Instant holds nanoseconds, and this is the value that
	// catches a serializer quietly rounding to milliseconds.
	private static final Instant OCCURRED_AT = Instant.parse("2026-08-22T09:15:30.123456789Z");

	private static final List<String> SEAT_IDS = List.of("A12", "A13");
	private static final BigDecimal AMOUNT = new BigDecimal("49.99");
	private static final int VERSION = 1;

	private final ObjectMapper mapper = EventJson.mapper();

	/** One populated instance of each of the seven message types. */
	static Stream<Arguments> allSevenMessages() {
		return Stream.of(
				named(new OrderCreated(
						MESSAGE_ID, ORDER_ID, OCCURRED_AT, VERSION,
						ORDER_ID, USER_ID, SHOW_ID, SEAT_IDS, AMOUNT)),
				named(new SeatsReserved(
						MESSAGE_ID, ORDER_ID, OCCURRED_AT, VERSION,
						ORDER_ID, SEAT_IDS, RESERVATION_ID, OCCURRED_AT.plusSeconds(120))),
				named(new SeatsRejected(
						MESSAGE_ID, ORDER_ID, OCCURRED_AT, VERSION,
						ORDER_ID, SEAT_IDS, RejectionReason.SEATS_ALREADY_HELD)),
				named(new PaymentSucceeded(
						MESSAGE_ID, ORDER_ID, OCCURRED_AT, VERSION,
						ORDER_ID, PAYMENT_ID, AMOUNT)),
				named(new PaymentFailed(
						MESSAGE_ID, ORDER_ID, OCCURRED_AT, VERSION,
						ORDER_ID, PaymentFailureReason.DECLINED)),
				named(new OrderConfirmed(
						MESSAGE_ID, ORDER_ID, OCCURRED_AT, VERSION,
						ORDER_ID, SEAT_IDS)),
				named(new OrderCancelled(
						MESSAGE_ID, ORDER_ID, OCCURRED_AT, VERSION,
						ORDER_ID, CancellationReason.PAYMENT_FAILED)));
	}

	// Labels each case with its type name, so a failure reads "SeatsReserved" rather than the
	// record's full toString.
	private static Arguments named(SagaEvent event) {
		return Arguments.of(Named.of(event.getClass().getSimpleName(), event));
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("allSevenMessages")
	@DisplayName("serializes and deserializes back to an equal object")
	void round_trips_to_an_equal_object(SagaEvent original) throws Exception {
		String json = mapper.writeValueAsString(original);

		SagaEvent restored = mapper.readValue(json, original.getClass());

		// Records derive equals() from every component, so this single assertion covers all of
		// them — including the ones a hand-written comparison would forget.
		assertThat(restored).isEqualTo(original);
	}

	@Test
	@DisplayName("keeps every seat, in order, through the round trip")
	void preserves_seat_list_contents() throws Exception {
		OrderCreated original = new OrderCreated(
				MESSAGE_ID, ORDER_ID, OCCURRED_AT, VERSION,
				ORDER_ID, USER_ID, SHOW_ID, List.of("A12", "A13", "B01"), AMOUNT);

		OrderCreated restored = mapper.readValue(mapper.writeValueAsString(original), OrderCreated.class);

		// Asserted separately from the equality check above because order matters and a list that
		// came back as a set, or reordered, would be a different kind of bug worth naming.
		assertThat(restored.seatIds()).containsExactly("A12", "A13", "B01");
	}

	@Test
	@DisplayName("keeps nanosecond precision on occurredAt")
	void preserves_instant_precision() throws Exception {
		OrderCreated original = new OrderCreated(
				MESSAGE_ID, ORDER_ID, OCCURRED_AT, VERSION,
				ORDER_ID, USER_ID, SHOW_ID, SEAT_IDS, AMOUNT);

		OrderCreated restored = mapper.readValue(mapper.writeValueAsString(original), OrderCreated.class);

		// WHY assert on the nano field specifically: a serializer that truncates to milliseconds
		// still produces an Instant that looks right at a glance. This fails loudly instead.
		assertThat(restored.occurredAt()).isEqualTo(OCCURRED_AT);
		assertThat(restored.occurredAt().getNano()).isEqualTo(123456789);
	}

	@Test
	@DisplayName("keeps money exact, with scale 2 and no scientific notation")
	void preserves_money_exactly() throws Exception {
		PaymentSucceeded original = new PaymentSucceeded(
				MESSAGE_ID, ORDER_ID, OCCURRED_AT, VERSION,
				ORDER_ID, PAYMENT_ID, new BigDecimal("100.00"));

		String json = mapper.writeValueAsString(original);
		PaymentSucceeded restored = mapper.readValue(json, PaymentSucceeded.class);

		// isEqualTo on BigDecimal compares scale too, so this asserts 100.00 came back as 100.00
		// rather than as 100.0 or 100 — which would be numerically right and contractually wrong.
		assertThat(restored.amount()).isEqualTo(new BigDecimal("100.00"));
		// And that WRITE_BIGDECIMAL_AS_PLAIN is doing its job on the wire itself.
		assertThat(json).contains("100.00").doesNotContain("1E+2");
	}

	@Test
	@DisplayName("writes timestamps as readable ISO-8601 text, not numbers")
	void writes_timestamps_as_iso_strings() throws Exception {
		OrderConfirmed original = new OrderConfirmed(
				MESSAGE_ID, ORDER_ID, OCCURRED_AT, VERSION, ORDER_ID, SEAT_IDS);

		String json = mapper.writeValueAsString(original);

		// The wire format is part of the contract, not an implementation detail: anything reading
		// these messages outside Java depends on it. Asserting on the text keeps a future Jackson
		// upgrade from silently changing it.
		assertThat(json).contains("\"occurredAt\":\"2026-08-22T09:15:30.123456789Z\"");
	}
}
