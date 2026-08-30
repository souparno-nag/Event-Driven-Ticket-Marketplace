package com.marketplace.inventory.consume;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.marketplace.events.OrderCreated;
import com.marketplace.events.Topics;
import com.marketplace.inventory.InventoryKafkaIT;
import com.marketplace.inventory.SeatingPlanFixture;

/**
 * SC-006: at-least-once delivery is made safe by the consumer, not the producer (contracts/inventory-
 * consumer.md's own opening line) — ten identical deliveries of the same message must produce exactly
 * the same effect as one, an interrupted delivery must still eventually produce its outcome, and two
 * genuinely different messages must never suppress one another.
 *
 * <p>WHY "publish the identical {@code OrderCreated} payload onto the real topic several times" is
 * how this class reproduces "redelivery", rather than manipulating Kafka's own rebalance or offset
 * machinery: from a consumer's point of view, at-least-once delivery already looks exactly like this
 * — the same {@code messageId} arriving as more than one distinct record. Kafka guarantees delivery at
 * least once by redelivering on any doubt, not by ever promising a record is unique; several records
 * on the topic carrying the same {@code messageId} is a completely faithful reproduction of what a
 * real broker outage, consumer restart, or rebalance produces, and it is fully within this test's own
 * control rather than depending on timing nobody can force reliably.
 *
 * <p>Expected to fail until User Story 3 exists: {@code OrderCreatedListener} (T178) and
 * {@code IdempotencyGuard} (T172, T174) don't exist yet, so nothing consumes {@code order.created} at
 * all and every {@code await*} call below times out. That is this checkpoint's correct state, not a
 * defect in this test.
 */
class IdempotencyIT extends InventoryKafkaIT {

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Test
	void tenDeliveriesOneEffect() {
		var show = SeatingPlanFixture.provisionShow(jdbcTemplate, "Idempotency-Ten", 1);
		String seat = show.seatLabels().get(0);
		UUID orderId = UUID.randomUUID();
		UUID messageId = UUID.randomUUID();
		OrderCreated event = new OrderCreated(
				messageId, orderId, Instant.now(), 1,
				orderId, UUID.randomUUID(), show.showId(), List.of(seat), new BigDecimal("10.00"));

		// The SAME record, published ten separate times -- ten distinct deliveries of one message,
		// not one delivery replayed by test code.
		for (int i = 0; i < 10; i++) {
			publishOrderCreated(event);
		}

		awaitSeatsReserved(orderId, Duration.ofSeconds(20));

		// awaitSeatsReserved returning proves at least one delivery produced the outcome. The counts
		// below are what actually prove the other nine did NOT each produce a second one -- a naive
		// consumer with no guard at all would also make the await above succeed, on its first
		// delivery, and only these counts would ever catch the other nine silently duplicating it.
		Integer reservationCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM reservations WHERE order_id = ?", Integer.class, orderId);
		assertThat(reservationCount).as("exactly one reservation for ten deliveries of one message").isEqualTo(1);

		Integer liveSeatClaims = jdbcTemplate.queryForObject("""
				SELECT count(*) FROM reservation_seats
				WHERE show_id = ? AND seat_label = ? AND released_at IS NULL
				""", Integer.class, show.showId(), seat);
		assertThat(liveSeatClaims).as("exactly one live hold on the seat").isEqualTo(1);

		Integer processedCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM processed_messages WHERE message_id = ?", Integer.class, messageId);
		assertThat(processedCount).as("the guard records this message id exactly once").isEqualTo(1);

		Integer outboxRowCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM outbox WHERE aggregate_id = ?", Integer.class, orderId);
		assertThat(outboxRowCount).as("exactly one announcement recorded for this order").isEqualTo(1);
	}

	@Test
	void distinctMessagesAreIndependent() {
		// Two GENUINELY different bookings, published together -- guards against an idempotency check
		// keyed on the wrong thing (the order id or the seat, say, rather than the message id), which
		// could make processing one silently suppress the other even though they share nothing.
		var show = SeatingPlanFixture.provisionShow(jdbcTemplate, "Idempotency-Distinct", 2);

		UUID firstOrderId = UUID.randomUUID();
		OrderCreated first = new OrderCreated(
				UUID.randomUUID(), firstOrderId, Instant.now(), 1,
				firstOrderId, UUID.randomUUID(), show.showId(), List.of(show.seatLabels().get(0)), new BigDecimal("10.00"));

		UUID secondOrderId = UUID.randomUUID();
		OrderCreated second = new OrderCreated(
				UUID.randomUUID(), secondOrderId, Instant.now(), 1,
				secondOrderId, UUID.randomUUID(), show.showId(), List.of(show.seatLabels().get(1)), new BigDecimal("20.00"));

		publishOrderCreated(first);
		publishOrderCreated(second);

		var firstReserved = awaitSeatsReserved(firstOrderId, Duration.ofSeconds(20));
		var secondReserved = awaitSeatsReserved(secondOrderId, Duration.ofSeconds(20));

		assertThat(firstReserved.seatIds()).containsExactly(show.seatLabels().get(0));
		assertThat(secondReserved.seatIds()).containsExactly(show.seatLabels().get(1));
	}

	@Test
	void outcomeSurvivesInterruption() {
		// "Interruption" reproduced here as a late, second delivery of a message whose first delivery
		// has ALREADY been fully processed and announced -- the shape a real interruption takes from
		// the consumer's own point of view: Kafka's offset commit happens only after this service's
		// listener returns normally (contracts/inventory-consumer.md's own note on this), so anything
		// that stops the listener from returning cleanly the first time -- a slow consumer missing a
		// rebalance deadline, a restart between commit and offset advance -- surfaces as exactly this:
		// the identical message arriving again, well after its own outcome already exists. The
		// guarantee under test is that this late redelivery is recognised as already handled and
		// produces no error and no second effect, rather than that the FIRST delivery can be made to
		// fail on demand, which no test can force without instrumenting the service internally.
		var show = SeatingPlanFixture.provisionShow(jdbcTemplate, "Idempotency-Interrupted", 1);
		String seat = show.seatLabels().get(0);
		UUID orderId = UUID.randomUUID();
		UUID messageId = UUID.randomUUID();
		OrderCreated event = new OrderCreated(
				messageId, orderId, Instant.now(), 1,
				orderId, UUID.randomUUID(), show.showId(), List.of(seat), new BigDecimal("10.00"));

		publishOrderCreated(event);
		awaitSeatsReserved(orderId, Duration.ofSeconds(20));

		// The late redelivery -- published only after the first delivery's own outcome is already
		// durable and announced.
		publishOrderCreated(event);

		// Give the late redelivery a real chance to (incorrectly) run before checking nothing changed.
		var received = poll(Topics.SEATS_REJECTED, Duration.ofSeconds(5),
				collected -> collected.stream().anyMatch(r -> r.key().equals(orderId.toString())));
		assertThat(received).as("a redelivery must never be announced as a refusal").isEmpty();

		Integer reservationCount = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM reservations WHERE order_id = ?", Integer.class, orderId);
		assertThat(reservationCount).as("the late redelivery created no second reservation").isEqualTo(1);
	}
}
