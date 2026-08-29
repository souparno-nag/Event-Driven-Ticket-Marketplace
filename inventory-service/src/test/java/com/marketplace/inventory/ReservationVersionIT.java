package com.marketplace.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.marketplace.events.RejectionReason;
import com.marketplace.inventory.service.ReservationOutcome;
import com.marketplace.inventory.service.ReservationService;

/**
 * SC-011: two concurrent attempts to advance one reservation result in exactly one succeeding and the
 * other being detected, retried once, and then either succeeding or failing visibly — with zero cases
 * of one silently overwriting the other.
 *
 * <p>WHY this scenario — two new orders racing over one already-lapsed seat — rather than a synthetic
 * update to an arbitrary field: it is the ONLY concurrent write to an existing {@code Reservation} row
 * this build step's own code path produces. Inline retirement of a lapsed reservation (FR-018) updates
 * the very same row a competing booking is also trying to retire, at the very same moment, whenever two
 * new orders discover the same stale hold simultaneously — which is exactly when FR-012's
 * {@code @Version} column earns its place rather than sitting unused until steps 4 and 5 introduce
 * other transitions.
 *
 * <p>The two threads cannot both win: only one seat exists, so the loser's retry — finding the winner's
 * fresh hold already in place — lands on an ordinary {@code SEATS_ALREADY_HELD} refusal. That is SC-011's
 * "or failing visibly" branch, not its "second failure surfaces as a processing failure" branch, which
 * describes a rarer three-way race this two-thread test does not attempt to engineer.
 */
class ReservationVersionIT extends HighConcurrencyIT {

	@Autowired
	ReservationService reservationService;

	@Autowired
	JdbcTemplate jdbcTemplate;

	private record Attempt(UUID orderId, ReservationOutcome outcome) {
	}

	@Test
	void exactlyOneWinnerAndNoSilentOverwrite() throws InterruptedException {
		var show = SeatingPlanFixture.provisionShow(jdbcTemplate, "VersionRace", 1);
		String seat = show.seatLabels().get(0);

		// A reservation whose hold already lapsed, covering the one seat both racing orders want --
		// planted directly and already COMMITTED, rather than waiting a real 120 seconds for a hold
		// to actually expire. Committed matters here specifically: the racing threads below open
		// their own, separate database connections, and must be able to see this row the instant
		// they start -- see LapsedReservationFixture's own Javadoc for why entityManager.persist(...)
		// inside this test method could not provide that.
		UUID staleOrderId = UUID.randomUUID();
		UUID staleReservationId = LapsedReservationFixture.plant(jdbcTemplate, show.showId(), seat, staleOrderId);

		Long versionBeforeRace = jdbcTemplate.queryForObject(
				"SELECT version FROM reservations WHERE reservation_id = ?", Long.class, staleReservationId);

		List<Attempt> attempts = ReservationContentionIT.runConcurrently(2, i -> {
			UUID orderId = UUID.randomUUID();
			return new Attempt(orderId, reservationService.decide(orderId, show.showId(), List.of(seat)));
		});

		assertThat(attempts).filteredOn(a -> a.outcome() instanceof ReservationOutcome.Reserved).hasSize(1);
		assertThat(attempts).filteredOn(a -> a.outcome() instanceof ReservationOutcome.Rejected).hasSize(1);
		assertThat(attempts).filteredOn(a -> a.outcome() instanceof ReservationOutcome.Rejected)
				.extracting(a -> ((ReservationOutcome.Rejected) a.outcome()).reason())
				.containsExactly(RejectionReason.SEATS_ALREADY_HELD);

		// The stale reservation must be retired exactly once -- version advanced by exactly one, not
		// left unchanged (meaning nobody retired it, which would make the winner's own insert fail the
		// live-seat constraint) and not advanced twice (meaning both racers' updates somehow both
		// landed, which @Version exists specifically to prevent).
		Long versionAfterRace = jdbcTemplate.queryForObject(
				"SELECT version FROM reservations WHERE reservation_id = ?", Long.class, staleReservationId);
		assertThat(versionAfterRace).isEqualTo(versionBeforeRace + 1);

		String statusAfterRace = jdbcTemplate.queryForObject(
				"SELECT status FROM reservations WHERE reservation_id = ?", String.class, staleReservationId);
		assertThat(statusAfterRace).isEqualTo("EXPIRED");

		// Exactly one live claim on the seat afterward -- the winner's, not the stale reservation's and
		// not two at once.
		Integer liveClaims = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM reservation_seats WHERE show_id = ? AND seat_label = ? AND released_at IS NULL",
				Integer.class, show.showId(), seat);
		assertThat(liveClaims).isEqualTo(1);
	}
}
