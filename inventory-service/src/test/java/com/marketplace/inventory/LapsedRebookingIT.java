package com.marketplace.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import com.marketplace.inventory.domain.Reservation;
import com.marketplace.inventory.domain.ReservationSeat;
import com.marketplace.inventory.service.ReservationOutcome;
import com.marketplace.inventory.service.ReservationService;

import jakarta.persistence.EntityManager;

/**
 * SC-016: a seat whose hold has lapsed is successfully rebooked by a different order on the first
 * attempt, with zero bookings failing because the previous reservation had not yet been retired —
 * measured with the periodic sweeper disabled.
 *
 * <p>Written and failing to compile until {@code ReservationService} and {@code ReservationOutcome}
 * exist (T158, T160).
 *
 * <p>{@code inventory.sweeper.enabled=false} is set for this whole test class on purpose, not merely
 * copied from habit: correctness here must not depend on a background sweeper ever having run
 * (research.md R6, FR-018). If disabling the sweeper broke this test, that would mean the inline
 * retirement path was never actually doing the retiring — the sweeper had been silently covering for
 * it. {@code LapsedReservationSweeper} does not exist yet either (T161), so this property currently
 * has no class reading it at all; it is set here anyway so the test already carries the correct
 * configuration the moment that class exists, rather than needing a second edit later to add it.
 */
@TestPropertySource(properties = "inventory.sweeper.enabled=false")
class LapsedRebookingIT extends InventoryIT {

	@Autowired
	ReservationService reservationService;

	@Autowired
	EntityManager entityManager;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Test
	void aLapsedSeatIsRebookedOnTheFirstAttempt() {
		var show = SeatingPlanFixture.provisionShow(jdbcTemplate, "LapsedRebooking", 1);
		String seat = show.seatLabels().get(0);

		UUID lapsedOrderId = UUID.randomUUID();
		Reservation lapsed = new Reservation(
				UUID.randomUUID(), lapsedOrderId, show.showId(), Instant.now().minusSeconds(1));
		entityManager.persist(lapsed);
		entityManager.persist(new ReservationSeat(lapsed.getReservationId(), seat, show.showId()));
		entityManager.flush();
		entityManager.clear();

		UUID newOrderId = UUID.randomUUID();
		ReservationOutcome outcome = reservationService.decide(newOrderId, show.showId(), List.of(seat));

		assertThat(outcome).isInstanceOf(ReservationOutcome.Reserved.class);

		String lapsedStatus = jdbcTemplate.queryForObject(
				"SELECT status FROM reservations WHERE reservation_id = ?", String.class, lapsed.getReservationId());
		assertThat(lapsedStatus).isEqualTo("EXPIRED");

		Integer newOrderLiveClaims = jdbcTemplate.queryForObject("""
				SELECT count(*) FROM reservation_seats rs
				JOIN reservations r ON r.reservation_id = rs.reservation_id
				WHERE r.order_id = ? AND rs.seat_label = ? AND rs.released_at IS NULL
				""", Integer.class, newOrderId, seat);
		assertThat(newOrderLiveClaims).isEqualTo(1);
	}
}
