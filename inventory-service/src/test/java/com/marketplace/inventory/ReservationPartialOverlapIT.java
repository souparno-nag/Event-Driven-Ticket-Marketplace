package com.marketplace.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.marketplace.inventory.service.ReservationOutcome;
import com.marketplace.inventory.service.ReservationService;

/**
 * SC-002: across at least 500 concurrent requests for PARTIALLY overlapping seat sets, zero requests
 * are granted a partial hold — every granted request holds every seat it asked for, and every refused
 * request holds none.
 *
 * <p>Written and failing to compile until {@code ReservationService} and {@code ReservationOutcome}
 * exist (T158, T160). See {@link ReservationContentionIT} for the full reasoning behind calling
 * {@code ReservationService} directly and provisioning an independent seat pool (research.md R10,
 * FR-041) — that reasoning applies identically here and is not repeated.
 *
 * <p>WHY this test matters as something SC-001 alone cannot prove: SC-001's ten seats are contended by
 * every single request, so a hold that is granted there is trivially all-or-nothing simply because
 * there is only one seat's worth of state to get right per request. This test's requests each ask for
 * THREE seats, deliberately overlapping their neighbours by two of them, which is what makes "some of
 * my seats but not all of them" a state the mechanism could produce if the underlying script checked
 * and set seats one at a time rather than as a single atomic pass (the exact trap
 * {@code contracts/seat-lock-scripts.md} names).
 */
class ReservationPartialOverlapIT extends InventoryIT {

	private static final int SEAT_COUNT = 100;
	private static final int REQUEST_COUNT = 500;

	@Autowired
	ReservationService reservationService;

	@Autowired
	JdbcTemplate jdbcTemplate;

	private record Attempt(UUID orderId, List<String> requestedSeats, ReservationOutcome outcome) {
	}

	@Test
	void noRequestIsEverGrantedAPartialHold() throws InterruptedException {
		var show = SeatingPlanFixture.provisionShow(jdbcTemplate, "PartialOverlap", SEAT_COUNT);
		List<String> labels = show.seatLabels();

		// Request i asks for three seats starting at index i (mod SEAT_COUNT), so request i and
		// request i+1 share two of their three seats -- deliberate, heavy, deterministic overlap
		// rather than random overlap that would make a failing run harder to reproduce.
		List<Attempt> attempts = ReservationContentionIT.runConcurrently(REQUEST_COUNT, i -> {
			UUID orderId = UUID.randomUUID();
			List<String> seats = List.of(
					labels.get(i % SEAT_COUNT),
					labels.get((i + 1) % SEAT_COUNT),
					labels.get((i + 2) % SEAT_COUNT));
			ReservationOutcome outcome = reservationService.decide(orderId, show.showId(), seats);
			return new Attempt(orderId, seats, outcome);
		});

		List<String> partialHolds = new ArrayList<>();
		for (Attempt attempt : attempts) {
			if (attempt.outcome() instanceof ReservationOutcome.Reserved) {
				Set<String> actuallyHeld = Set.copyOf(jdbcTemplate.queryForList("""
						SELECT rs.seat_label FROM reservation_seats rs
						JOIN reservations r ON r.reservation_id = rs.reservation_id
						WHERE r.order_id = ? AND rs.released_at IS NULL
						""", String.class, attempt.orderId()));
				if (!actuallyHeld.equals(Set.copyOf(attempt.requestedSeats()))) {
					partialHolds.add("order " + attempt.orderId() + " requested " + attempt.requestedSeats()
							+ " but holds " + actuallyHeld);
				}
			} else {
				// A refusal must hold NOTHING -- not the two seats it happened to win before
				// discovering the third was taken, which is precisely what a check-and-set-as-you-go
				// script would produce.
				Integer heldCount = jdbcTemplate.queryForObject(
						"SELECT count(*) FROM reservations WHERE order_id = ?", Integer.class, attempt.orderId());
				if (heldCount != 0) {
					partialHolds.add("order " + attempt.orderId() + " was refused but holds a reservation anyway");
				}
			}
		}

		assertThat(partialHolds).as("requests granted or refused a PARTIAL hold").isEmpty();
	}
}
