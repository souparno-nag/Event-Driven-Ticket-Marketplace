package com.marketplace.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.marketplace.inventory.service.ReservationOutcome;
import com.marketplace.inventory.service.ReservationService;

/**
 * SC-003: across at least 500 concurrent requests for ENTIRELY disjoint seat sets, drawn from a pool
 * this test provisions for itself, 100% are granted — demonstrating that no contention is invented by
 * the holding mechanism itself.
 *
 * <p>Written and failing to compile until {@code ReservationService} and {@code ReservationOutcome}
 * exist (T158, T160). See {@link ReservationContentionIT} for the shared reasoning behind calling
 * {@code ReservationService} directly and provisioning an independent pool (research.md R10, FR-041).
 *
 * <p>WHY this test matters as much as {@link ReservationContentionIT}, despite asserting the opposite
 * outcome: a lock that serialises every booking for a show through one coarse lock — a Redlock-style
 * whole-show mutex, say, rejected in research.md R1 for exactly this reason — would still pass
 * SC-001, because SC-001's contention is genuine and a coarse lock cannot double-book seats it
 * processes one at a time. It only fails HERE, where five hundred requests that share nothing should
 * all succeed simultaneously and a coarse lock would instead serialise them, likely timing out or
 * queuing rather than granting all five hundred. This test is what catches "correct because nothing
 * ever double-books" from "correct because it also never lets two things happen at once."
 */
class ReservationDisjointIT extends InventoryIT {

	private static final int REQUEST_COUNT = 500;

	@Autowired
	ReservationService reservationService;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Test
	void allFiveHundredDisjointRequestsSucceed() throws InterruptedException {
		// Exactly one seat per request, and exactly REQUEST_COUNT seats provisioned -- there is
		// structurally no seat two requests could ever both ask for.
		var show = SeatingPlanFixture.provisionShow(jdbcTemplate, "Disjoint", REQUEST_COUNT);
		List<String> labels = show.seatLabels();

		List<ReservationOutcome> outcomes = ReservationContentionIT.runConcurrently(REQUEST_COUNT,
				i -> reservationService.decide(UUID.randomUUID(), show.showId(), List.of(labels.get(i))));

		assertThat(outcomes).hasSize(REQUEST_COUNT)
				.allMatch(o -> o instanceof ReservationOutcome.Reserved,
						"every disjoint request must be granted -- none of them contend for anything");

		Integer liveClaims = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM reservation_seats WHERE show_id = ? AND released_at IS NULL",
				Integer.class, show.showId());
		assertThat(liveClaims).isEqualTo(REQUEST_COUNT);
	}
}
