package com.marketplace.inventory;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Plants an already-lapsed reservation directly, for tests that need to arrange "a hold that expired
 * a moment ago" without waiting a real 120 seconds for one to actually happen.
 *
 * <p>Inserts via plain {@link JdbcTemplate}, matching {@link SeatingPlanFixture}'s own choice and for
 * a closely related reason, sharpened here by a genuine bug this class's absence caused: a test
 * spinning up SEPARATE virtual threads to race {@code ReservationService.decide(...)} needs the
 * planted row to be visible from a DIFFERENT database connection than the one that inserted it —
 * which means it must already be COMMITTED, not merely written inside a Spring-managed test
 * transaction that stays open (and invisible to every other connection) until the test method returns
 * and then rolls back. {@code JdbcTemplate}, with no transaction active, commits each statement
 * immediately; {@code EntityManager.persist(...)} inside a {@code @Transactional} test method does the
 * opposite of what these tests need. This was found directly, not assumed: an earlier version using
 * {@code entityManager.persist(...)} left the racing threads unable to see the row they were meant to
 * contend over at all.
 */
final class LapsedReservationFixture {

	private LapsedReservationFixture() {
	}

	/**
	 * @return the id of the newly planted reservation, already {@code HELD} with a
	 *         {@code lock_expires_at} one second in the past
	 */
	static UUID plant(JdbcTemplate jdbc, UUID showId, String seatLabel, UUID orderId) {
		UUID reservationId = UUID.randomUUID();
		Timestamp lockExpiresAt = Timestamp.from(Instant.now().minusSeconds(1));

		jdbc.update("""
				INSERT INTO reservations (reservation_id, order_id, show_id, status, lock_expires_at)
				VALUES (?, ?, ?, 'HELD', ?)
				""", reservationId, orderId, showId, lockExpiresAt);

		jdbc.update("""
				INSERT INTO reservation_seats (reservation_id, seat_label, show_id)
				VALUES (?, ?, ?)
				""", reservationId, seatLabel, showId);

		return reservationId;
	}
}
