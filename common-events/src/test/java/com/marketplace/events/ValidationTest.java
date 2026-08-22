package com.marketplace.events;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Every way to build an invalid message is rejected at construction (data-model.md rules 1–6).
 *
 * <p>WHY these assert through the records rather than calling {@link Validation} directly: the rule
 * being tested is not "the helper works" but "the record uses the helper". A record that forgot to
 * validate would pass a test aimed at the helper and fail this one, which is the failure worth
 * catching — the helpers are trivial, the wiring is what gets missed.
 */
class ValidationTest {

	private static final UUID ORDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
	private static final UUID USER_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");
	private static final UUID SHOW_ID = UUID.fromString("33333333-3333-3333-3333-333333333333");
	private static final UUID RESERVATION_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");
	private static final UUID MESSAGE_ID = UUID.fromString("66666666-6666-6666-6666-666666666666");
	private static final UUID OTHER_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");

	private static final Instant OCCURRED_AT = Instant.parse("2026-08-22T09:15:30.123456789Z");
	private static final List<String> SEAT_IDS = List.of("A12", "A13");
	private static final BigDecimal AMOUNT = new BigDecimal("49.99");
	private static final int VERSION = 1;

	@Nested
	@DisplayName("rule 1 — nothing is optional")
	class NonNull {

		@Test
		void rejects_a_null_message_id() {
			assertThatThrownBy(() -> new OrderCreated(
					null, ORDER_ID, OCCURRED_AT, VERSION, ORDER_ID, USER_ID, SHOW_ID, SEAT_IDS, AMOUNT))
					.isInstanceOf(NullPointerException.class)
					.hasMessageContaining("messageId");
		}

		@Test
		void rejects_a_null_occurred_at() {
			assertThatThrownBy(() -> new OrderCreated(
					MESSAGE_ID, ORDER_ID, null, VERSION, ORDER_ID, USER_ID, SHOW_ID, SEAT_IDS, AMOUNT))
					.isInstanceOf(NullPointerException.class)
					.hasMessageContaining("occurredAt");
		}

		@Test
		void rejects_a_null_reason() {
			// The enum-carrying records have no other way to say "no reason given", and a
			// cancellation without a cause is exactly the message nobody can act on later.
			assertThatThrownBy(() -> new OrderCancelled(
					MESSAGE_ID, ORDER_ID, OCCURRED_AT, VERSION, ORDER_ID, null))
					.isInstanceOf(NullPointerException.class)
					.hasMessageContaining("reason");
		}
	}

	@Nested
	@DisplayName("rule 2 — sagaId must equal orderId")
	class SagaCorrelation {

		@Test
		void rejects_a_saga_id_that_does_not_match_the_order() {
			// The failure this prevents is silent: a mismatched key hashes to a different partition,
			// so the message arrives out of order relative to its own saga with no error anywhere.
			assertThatThrownBy(() -> new OrderCreated(
					MESSAGE_ID, OTHER_ID, OCCURRED_AT, VERSION, ORDER_ID, USER_ID, SHOW_ID, SEAT_IDS, AMOUNT))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("sagaId must equal orderId");
		}
	}

	@Nested
	@DisplayName("rule 3 — seat lists are non-empty, distinct, and immutable")
	class Seats {

		@Test
		void rejects_an_empty_seat_list() {
			assertThatThrownBy(() -> new OrderCreated(
					MESSAGE_ID, ORDER_ID, OCCURRED_AT, VERSION, ORDER_ID, USER_ID, SHOW_ID, List.of(), AMOUNT))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("must not be empty");
		}

		@Test
		void rejects_duplicate_seats() {
			// Rejected rather than collapsed: quietly deduplicating would charge for two seats and
			// reserve one. Arrays.asList because List.of already forbids nothing of the sort.
			assertThatThrownBy(() -> new OrderCreated(
					MESSAGE_ID, ORDER_ID, OCCURRED_AT, VERSION, ORDER_ID, USER_ID, SHOW_ID,
					Arrays.asList("A12", "A12"), AMOUNT))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("duplicates");
		}

		@Test
		@DisplayName("copies the list, so a caller cannot mutate a published message")
		void copies_the_seat_list_defensively() {
			List<String> mutable = new ArrayList<>(List.of("A12"));
			OrderCreated event = new OrderCreated(
					MESSAGE_ID, ORDER_ID, OCCURRED_AT, VERSION, ORDER_ID, USER_ID, SHOW_ID, mutable, AMOUNT);

			mutable.add("B07");

			// If the record had stored the caller's list instead of a copy, the published message
			// would now hold two seats. This is the escaping-reference bug, asserted rather than
			// assumed.
			org.assertj.core.api.Assertions.assertThat(event.seatIds()).containsExactly("A12");
			assertThatThrownBy(() -> event.seatIds().add("C01"))
					.isInstanceOf(UnsupportedOperationException.class);
		}
	}

	@Nested
	@DisplayName("rule 4 — money is non-negative with scale exactly 2")
	class Money {

		@Test
		void rejects_a_negative_amount() {
			assertThatThrownBy(() -> new OrderCreated(
					MESSAGE_ID, ORDER_ID, OCCURRED_AT, VERSION, ORDER_ID, USER_ID, SHOW_ID, SEAT_IDS,
					new BigDecimal("-1.00")))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("must not be negative");
		}

		@Test
		void rejects_an_amount_with_too_few_decimal_places() {
			// 49.9 is a perfectly good number and an invalid amount here, because BigDecimal.equals
			// compares scale — so without a canonical scale a message can round-trip and come back
			// unequal to itself.
			assertThatThrownBy(() -> new OrderCreated(
					MESSAGE_ID, ORDER_ID, OCCURRED_AT, VERSION, ORDER_ID, USER_ID, SHOW_ID, SEAT_IDS,
					new BigDecimal("49.9")))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("scale exactly 2");
		}

		@Test
		void rejects_an_amount_with_too_many_decimal_places() {
			assertThatThrownBy(() -> new OrderCreated(
					MESSAGE_ID, ORDER_ID, OCCURRED_AT, VERSION, ORDER_ID, USER_ID, SHOW_ID, SEAT_IDS,
					new BigDecimal("49.990")))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("scale exactly 2");
		}

		@Test
		@DisplayName("accepts zero — a free ticket is a real order")
		void accepts_a_zero_amount() {
			new OrderCreated(MESSAGE_ID, ORDER_ID, OCCURRED_AT, VERSION, ORDER_ID, USER_ID, SHOW_ID,
					SEAT_IDS, new BigDecimal("0.00"));
		}
	}

	@Nested
	@DisplayName("rule 5 — schemaVersion starts at 1")
	class SchemaVersion {

		@Test
		void rejects_version_zero() {
			// Zero is what an uninitialised int already is, so accepting it would make "the producer
			// forgot" indistinguishable from a deliberate value.
			assertThatThrownBy(() -> new OrderCreated(
					MESSAGE_ID, ORDER_ID, OCCURRED_AT, 0, ORDER_ID, USER_ID, SHOW_ID, SEAT_IDS, AMOUNT))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("schemaVersion");
		}

		@Test
		void rejects_a_negative_version() {
			assertThatThrownBy(() -> new OrderCreated(
					MESSAGE_ID, ORDER_ID, OCCURRED_AT, -1, ORDER_ID, USER_ID, SHOW_ID, SEAT_IDS, AMOUNT))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("schemaVersion");
		}
	}

	@Nested
	@DisplayName("rule 6 — a seat hold must expire after the fact that created it")
	class LockExpiry {

		@Test
		void rejects_an_expiry_before_the_message_occurred() {
			assertThatThrownBy(() -> new SeatsReserved(
					MESSAGE_ID, ORDER_ID, OCCURRED_AT, VERSION, ORDER_ID, SEAT_IDS, RESERVATION_ID,
					OCCURRED_AT.minusSeconds(1)))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("lockExpiresAt");
		}

		@Test
		@DisplayName("rejects an expiry equal to occurredAt — strictly after, not merely not-before")
		void rejects_an_expiry_equal_to_occurred_at() {
			// A hold that expires the instant it is taken is already expired, so the step-4 fencing
			// check could never succeed. Excluding equality makes that unrepresentable.
			assertThatThrownBy(() -> new SeatsReserved(
					MESSAGE_ID, ORDER_ID, OCCURRED_AT, VERSION, ORDER_ID, SEAT_IDS, RESERVATION_ID,
					OCCURRED_AT))
					.isInstanceOf(IllegalArgumentException.class)
					.hasMessageContaining("lockExpiresAt");
		}
	}
}
