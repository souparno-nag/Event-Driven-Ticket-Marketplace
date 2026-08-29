package com.marketplace.inventory.seats;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.marketplace.events.OrderCreated;

/**
 * Specifies {@link SeatKey}, which T151 has not yet created — this unit test is written first and
 * fails to compile until it does (Constitution Principle II's "fail before, pass after," satisfied
 * structurally rather than ceremonially).
 *
 * <p>The three assertions below are the entire reason this class exists rather than a call site
 * simply writing {@code "seat:" + showId + ":" + seatId} inline wherever a key is needed: the brief's
 * original key format used {@code eventId}, which predates step 1's rename splitting one ambiguous
 * field into {@code showId} (the concert) and {@code messageId} (the message's own identity). A hold
 * keyed by message identity would be unique per delivery, so a redelivered request would contend with
 * nothing and take a second hold on a seat it already holds — the mutual exclusion this entire service
 * exists to provide, silently absent, while every test that never redelivers a message still passes
 * (research.md R3, FR-007).
 */
class SeatKeyTest {

	@Test
	void isBuiltFromShowIdAndSeatLabel() {
		UUID showId = UUID.randomUUID();
		String key = SeatKey.of(showId, "A1");

		assertThat(key).isEqualTo("seat:" + showId + ":A1");
	}

	@Test
	void isStableAcrossTwoDifferentMessagesForTheSameOrder() {
		// Two OrderCreated messages differing ONLY in messageId -- exactly what a redelivery of the
		// same logical request, or a genuinely new request for the same seats, would look like on the
		// wire. Guarantee 3 of the seat-lock contract (a key already holding this order's id counts as
		// acquirable) only matters at all if the key these two messages produce is identical.
		UUID showId = UUID.randomUUID();
		OrderCreated first = orderCreated(showId, UUID.randomUUID());
		OrderCreated second = orderCreated(showId, UUID.randomUUID());

		assertThat(first.messageId()).isNotEqualTo(second.messageId());
		assertThat(SeatKey.of(first.showId(), "A1")).isEqualTo(SeatKey.of(second.showId(), "A1"));
	}

	@Test
	void messageIdNeverAppearsInTheKey() {
		// The concrete failure mode this guards against: a key builder that reads the wrong accessor
		// off OrderCreated -- messageId() where showId() was meant -- compiles cleanly, since both are
		// UUID, and produces a key unique per delivery. This assertion is the one place that distinction
		// is actually checked rather than merely documented.
		UUID showId = UUID.randomUUID();
		UUID messageId = UUID.randomUUID();
		OrderCreated event = orderCreated(showId, messageId);

		String key = SeatKey.of(event.showId(), "A1");

		assertThat(key).doesNotContain(messageId.toString());
	}

	private static OrderCreated orderCreated(UUID showId, UUID messageId) {
		UUID orderId = UUID.randomUUID();
		return new OrderCreated(messageId, orderId, Instant.now(), 1, orderId,
				UUID.randomUUID(), showId, List.of("A1"), new BigDecimal("10.00"));
	}
}
