package com.marketplace.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

import com.marketplace.inventory.domain.Reservation;
import com.marketplace.inventory.domain.ReservationSeat;

import jakarta.persistence.EntityManager;

/**
 * SC-017: attempting to record two live reservations covering the same seat of the same show is
 * rejected by the durable store itself, verified by bypassing the fast contention store entirely.
 *
 * <p>Unlike every other test in this batch, this one needs none of the classes still unwritten this
 * build step (no {@code ReservationService}, no Redis, no Lua). {@link Reservation} and
 * {@link ReservationSeat} (T126, T127) are all it takes to attempt the exact write
 * {@code ux_reservation_seat_live} exists to refuse — so this test is written to PASS now, proving the
 * guarantee that survives {@code lock_seats.lua} being wrong already holds before that script has even
 * been written (research.md R5; {@code V2__create_reservations.sql}).
 *
 * <p>Testing through the normal path — via Redis and the eventual {@code ReservationService} — would
 * only prove Redis works, which is precisely what this test must NOT depend on: the whole point of the
 * constraint is that it holds even when the mechanism above it does not.
 */
class LiveSeatConstraintIT extends InventoryIT {

	@Autowired
	EntityManager entityManager;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Test
	@Transactional
	void aSecondLiveClaimOnOneSeatIsRejectedByTheDatabaseAlone() {
		var show = SeatingPlanFixture.provisionShow(jdbcTemplate, "LiveSeatConstraint", 1);
		String seat = show.seatLabels().get(0);

		Reservation first = new Reservation(
				UUID.randomUUID(), UUID.randomUUID(), show.showId(), Instant.now().plusSeconds(120));
		entityManager.persist(first);
		entityManager.persist(new ReservationSeat(first.getReservationId(), seat, show.showId()));
		entityManager.flush();

		Reservation second = new Reservation(
				UUID.randomUUID(), UUID.randomUUID(), show.showId(), Instant.now().plusSeconds(120));
		entityManager.persist(second);
		entityManager.persist(new ReservationSeat(second.getReservationId(), seat, show.showId()));

		// The unique index, not any application-level check, is what must reject this -- so the
		// assertion looks for the constraint's own name in the failure, confirming the database is
		// what refused it rather than some other unrelated problem in the write.
		assertThatThrownBy(entityManager::flush)
				.hasStackTraceContaining("ux_reservation_seat_live");
	}

	@Test
	@Transactional
	void releasingTheFirstClaimLetsANewOneThroughImmediately() {
		// The opposite-direction check: the index must not simply forbid the seat forever once one
		// reservation has touched it. Releasing the first claim (setting released_at) must free the
		// (show_id, seat_label) pair for a genuinely new live claim in the same test, with no special
		// handling required.
		var show = SeatingPlanFixture.provisionShow(jdbcTemplate, "LiveSeatConstraintRelease", 1);
		String seat = show.seatLabels().get(0);

		Reservation first = new Reservation(
				UUID.randomUUID(), UUID.randomUUID(), show.showId(), Instant.now().plusSeconds(120));
		entityManager.persist(first);
		ReservationSeat firstSeat = new ReservationSeat(first.getReservationId(), seat, show.showId());
		entityManager.persist(firstSeat);
		entityManager.flush();

		firstSeat.release(Instant.now());
		first.expire();
		entityManager.flush();

		Reservation second = new Reservation(
				UUID.randomUUID(), UUID.randomUUID(), show.showId(), Instant.now().plusSeconds(120));
		entityManager.persist(second);
		entityManager.persist(new ReservationSeat(second.getReservationId(), seat, show.showId()));
		entityManager.flush();

		Integer liveClaims = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM reservation_seats WHERE show_id = ? AND seat_label = ? AND released_at IS NULL",
				Integer.class, show.showId(), seat);
		assertThat(liveClaims).isEqualTo(1);
	}
}
