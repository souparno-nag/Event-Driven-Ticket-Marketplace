package com.marketplace.inventory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Provisions a show and a seat pool for one test's exclusive use, sized to the contention it intends
 * to create.
 *
 * <p>WHY this exists rather than seeding a larger venue in {@code V1__create_seating_plan.sql}: the
 * seeded plan carries exactly what the RUNNING SYSTEM needs — the ten-seat show the step-9 load test
 * contends over, plus one further show for per-show scoping — and nothing more, deliberately
 * (FR-036). SC-002 and SC-003 need pools of hundreds of seats. Growing the seed to satisfy the
 * biggest test's appetite was rejected during the spec's clarification pass: tests sharing one large
 * pool interfere with each other unless each carefully partitions it, which makes a failure's
 * reproducibility depend on execution order — the hardest kind of concurrency bug to chase — and it
 * would ship data to every environment that exists only to be tested against, including production.
 *
 * <p>WHY this inserts rows via {@link JdbcTemplate} rather than through {@link com.marketplace.inventory.domain.Show}
 * and {@link com.marketplace.inventory.domain.ShowSeat} as JPA entities: those two classes are
 * deliberately read-only from the application's point of view (see their own Javadoc, T125) — nothing
 * in this service's own code ever calls {@code save()} against either, because the only legitimate way
 * a show or a seat label comes to exist is a migration. Giving them a save-capable API purely so tests
 * could use it would widen a surface that is narrow on purpose for a reason that has nothing to do
 * with testing. Inserting via plain SQL, the same way {@code V1__create_seating_plan.sql}'s own seed
 * does, respects that boundary: a test fixture is allowed to do what a migration does, not what
 * application code is forbidden from doing.
 */
public final class SeatingPlanFixture {

	private SeatingPlanFixture() {
	}

	/** A freshly provisioned show and the exact seat labels created for it, so a caller can request
	 * specific seats — or the whole pool, or a deliberately overlapping subset with another call's
	 * pool — without having to guess a naming scheme. */
	public record ProvisionedShow(UUID showId, List<String> seatLabels) {
	}

	/**
	 * Creates a new show named {@code namePrefix} (suffixed with its own id, so concurrently running
	 * tests never collide on the {@code name} column even though nothing enforces uniqueness on it)
	 * with exactly {@code seatCount} seats labelled {@code seat-0} through {@code seat-(seatCount-1)}.
	 *
	 * @param jdbc       reaches the same database and schema the test's own Spring context is wired to
	 * @param namePrefix a human-readable label for the show, for diagnosis only — nothing in this
	 *                   service branches on a show's name
	 * @param seatCount  how many seats to create, sized by the caller to the contention it intends to
	 *                   create (FR-041) — not a shared default, so a test's own seat count is
	 *                   legible at its own call site
	 * @return the new show's id and the exact list of seat labels created for it
	 */
	public static ProvisionedShow provisionShow(JdbcTemplate jdbc, String namePrefix, int seatCount) {
		UUID showId = UUID.randomUUID();
		jdbc.update("INSERT INTO shows (show_id, name) VALUES (?, ?)", showId, namePrefix + "-" + showId);

		List<String> labels = new ArrayList<>(seatCount);
		List<Object[]> batchArgs = new ArrayList<>(seatCount);
		for (int i = 0; i < seatCount; i++) {
			String label = "seat-" + i;
			labels.add(label);
			batchArgs.add(new Object[] {showId, label});
		}
		// A single batched INSERT rather than seatCount individual round trips -- provisioning SC-003's
		// 500-seat pool one row at a time would make the fixture itself a meaningful fraction of that
		// test's own running time, for a step the test is not actually trying to measure.
		jdbc.batchUpdate("INSERT INTO show_seats (show_id, seat_label) VALUES (?, ?)", batchArgs);

		return new ProvisionedShow(showId, List.copyOf(labels));
	}
}
