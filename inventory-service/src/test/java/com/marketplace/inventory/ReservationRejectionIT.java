package com.marketplace.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.marketplace.events.RejectionReason;
import com.marketplace.inventory.domain.ReservationRepository;
import com.marketplace.inventory.service.ReservationOutcome;
import com.marketplace.inventory.service.ReservationService;

/**
 * SC-008: each of the three refusal causes is produced by exactly the condition that names it, and by
 * no other — an unknown show, a real show with a fabricated seat label, and seats genuinely already
 * held. Every refusal must also report the FULL requested seat set (never a filtered-down subset of
 * just the unavailable ones) and leave every requested seat completely unheld, including a seat that
 * was free at the moment of the attempt (FR-023).
 *
 * <p>Asserts at three different levels for each cause, not just on the returned {@link ReservationOutcome}:
 * the outcome's own type and reason, that no {@code reservations} row was ever written (a refusal
 * writes no reservation row — data-model.md), that no requested seat is left {@code HELD}, and that the
 * outbox row announcing the refusal carries every requested seat label, not merely the ones that
 * actually caused the refusal. A test stopping at the first of these would leave the other three
 * guarantees SC-008 names completely unverified.
 */
class ReservationRejectionIT extends InventoryIT {

	@Autowired
	ReservationService reservationService;

	@Autowired
	ReservationRepository reservationRepository;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Test
	void unknownShowIsRejectedAsShowNotFound() {
		UUID showId = UUID.randomUUID(); // never provisioned -- no row in `shows` names this id
		UUID orderId = UUID.randomUUID();
		List<String> seatIds = List.of("A1", "A2");

		ReservationOutcome outcome = reservationService.decide(orderId, showId, seatIds);

		assertThat(outcome).isInstanceOf(ReservationOutcome.Rejected.class);
		assertThat(((ReservationOutcome.Rejected) outcome).reason()).isEqualTo(RejectionReason.SHOW_NOT_FOUND);
		assertNoReservationWritten(orderId);
		assertNoSeatHeld(showId, seatIds);
		assertOutboxReportsFullSeatSet(orderId, seatIds);
	}

	@Test
	void unknownSeatLabelInARealShowIsRejectedAsSeatsNotFound() {
		var show = SeatingPlanFixture.provisionShow(jdbcTemplate, "Rejection-SeatsNotFound", 1);
		UUID orderId = UUID.randomUUID();
		// The show is real; "ghost-seat" is not one of the labels SeatingPlanFixture just created for
		// it -- exactly the "show exists, seat doesn't" shape SEATS_NOT_FOUND exists to distinguish
		// from SHOW_NOT_FOUND.
		List<String> seatIds = List.of("ghost-seat");

		ReservationOutcome outcome = reservationService.decide(orderId, show.showId(), seatIds);

		assertThat(outcome).isInstanceOf(ReservationOutcome.Rejected.class);
		assertThat(((ReservationOutcome.Rejected) outcome).reason()).isEqualTo(RejectionReason.SEATS_NOT_FOUND);
		assertNoReservationWritten(orderId);
		assertNoSeatHeld(show.showId(), seatIds);
		assertOutboxReportsFullSeatSet(orderId, seatIds);
	}

	@Test
	void seatAlreadyHeldByAnotherOrderIsRejectedAsSeatsAlreadyHeld() {
		var show = SeatingPlanFixture.provisionShow(jdbcTemplate, "Rejection-AlreadyHeld", 2);
		String heldSeat = show.seatLabels().get(0);
		String freeSeat = show.seatLabels().get(1);

		UUID holderOrderId = UUID.randomUUID();
		ReservationOutcome holderOutcome = reservationService.decide(holderOrderId, show.showId(), List.of(heldSeat));
		assertThat(holderOutcome).isInstanceOf(ReservationOutcome.Reserved.class);

		// The challenger asks for BOTH the already-held seat and a seat that is genuinely still free.
		// All-or-nothing means the whole request is refused -- and specifically, freeSeat must come
		// out of this exactly as unheld as it went in, even though nothing was ever wrong with it on
		// its own.
		UUID challengerOrderId = UUID.randomUUID();
		List<String> requested = List.of(heldSeat, freeSeat);
		ReservationOutcome outcome = reservationService.decide(challengerOrderId, show.showId(), requested);

		assertThat(outcome).isInstanceOf(ReservationOutcome.Rejected.class);
		assertThat(((ReservationOutcome.Rejected) outcome).reason()).isEqualTo(RejectionReason.SEATS_ALREADY_HELD);
		assertNoReservationWritten(challengerOrderId);
		assertNoSeatHeld(show.showId(), List.of(freeSeat));
		assertOutboxReportsFullSeatSet(challengerOrderId, requested);
	}

	private void assertNoReservationWritten(UUID orderId) {
		assertThat(reservationRepository.findByOrderId(orderId)).isEmpty();
	}

	/**
	 * "Not held" here means no LIVE claim exists for this (show, seat) pair -- {@code released_at IS
	 * NULL} is exactly the condition {@code ux_reservation_seat_live} itself enforces, so this check
	 * asks the same question the database's own constraint would.
	 */
	private void assertNoSeatHeld(UUID showId, List<String> seatIds) {
		for (String seatId : seatIds) {
			Integer liveClaims = jdbcTemplate.queryForObject("""
					SELECT count(*) FROM reservation_seats
					WHERE show_id = ? AND seat_label = ? AND released_at IS NULL
					""", Integer.class, showId, seatId);
			assertThat(liveClaims).as("live claims on seat %s", seatId).isEqualTo(0);
		}
	}

	/**
	 * Reads the outbox row's stored payload directly via JDBC rather than through
	 * {@code OutboxRepository}, the same way {@code ReservationContentionIT} reaches past the JPA layer
	 * for its own database-level assertions -- this check is about what actually landed in the
	 * {@code outbox} table's {@code jsonb} column, not about what an entity's getters report.
	 */
	private void assertOutboxReportsFullSeatSet(UUID orderId, List<String> seatIds) {
		String payload = jdbcTemplate.queryForObject(
				"SELECT payload FROM outbox WHERE aggregate_id = ?", String.class, orderId);
		for (String seatId : seatIds) {
			assertThat(payload).as("payload for order %s should report every requested seat", orderId)
					.contains(seatId);
		}
	}
}
