package com.marketplace.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.RepeatedTest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.marketplace.events.RejectionReason;
import com.marketplace.inventory.service.ReservationOutcome;
import com.marketplace.inventory.service.ReservationService;

/**
 * SC-001: 1,000 concurrent booking requests against a pool of exactly 10 seats. Exactly 10 must be
 * granted, exactly 990 refused, and no seat ever appears in more than one granted hold — repeatable
 * across at least 20 consecutive runs with no run deviating.
 *
 * <p>{@code ReservationService} (T160) exists and this file compiles, but the two Lua scripts it
 * relies on are still empty stubs (T152) awaiting the developer exercise (T156) — until then, every
 * booking attempt is refused, since an empty script returns nothing at all rather than the {@code 1}
 * a genuinely free seat should produce. That is the intended state this test is written to catch the
 * MOMENT it stops being true, not something this file works around.
 *
 * <p>Calls {@code ReservationService} DIRECTLY rather than through Kafka, per research.md R10 and
 * FR-040: {@code order.created} has three partitions, frozen in step 1, which caps genuinely
 * simultaneous work through the channel at three regardless of how many messages arrive. A broken
 * all-or-nothing hold could survive a three-way race by luck; it cannot survive this one.
 *
 * <p>Provisions its OWN ten-seat show via {@link SeatingPlanFixture} rather than the seeded "Load Test
 * Hall" (FR-041) — that seeded show is reserved for the step-9 k6 load test specifically, and a test
 * sharing it here would make that later test's own results depend on whether this one has already run.
 */
class ReservationContentionIT extends HighConcurrencyIT {

	private static final int SEAT_COUNT = 10;
	private static final int REQUEST_COUNT = 1_000;

	@Autowired
	ReservationService reservationService;

	@Autowired
	JdbcTemplate jdbcTemplate;

	/**
	 * Repeated 20 times, per SC-001's own repeatability requirement — a single pass could plausibly
	 * succeed by luck even with a subtly broken script; twenty in a row is what makes "by luck" an
	 * implausible explanation for a passing result.
	 *
	 * <p>Each repetition provisions a FRESH show. Reusing one across repetitions would mean every seat
	 * is already permanently held by the previous repetition's ten winners — a hold's 120-second
	 * lifetime is far longer than this test takes to run, so a shared show would make every repetition
	 * after the first trivially fail with zero seats free to contend for.
	 */
	@RepeatedTest(20)
	void exactlyTenOfAThousandSucceedAgainstTenSeats() throws InterruptedException {
		var show = SeatingPlanFixture.provisionShow(jdbcTemplate, "Contention", SEAT_COUNT);

		// Every one of the REQUEST_COUNT threads targets exactly one seat, assigned round-robin across
		// the ten so each seat draws exactly 100 simultaneous contenders -- genuine, even contention on
		// every seat, not just on one of them.
		List<ReservationOutcome> outcomes = runConcurrently(REQUEST_COUNT,
				i -> reservationService.decide(UUID.randomUUID(), show.showId(),
						List.of(show.seatLabels().get(i % SEAT_COUNT))));

		long granted = outcomes.stream().filter(o -> o instanceof ReservationOutcome.Reserved).count();
		long refused = outcomes.stream().filter(o -> o instanceof ReservationOutcome.Rejected).count();

		assertThat(granted).isEqualTo(SEAT_COUNT);
		assertThat(refused).isEqualTo(REQUEST_COUNT - SEAT_COUNT);

		// Every refusal must be for the stated reason a contended seat produces, never a different
		// cause masquerading as contention -- FR-023's three causes mean different things, and a
		// mislabelled one would be a bug this assertion exists to catch.
		assertThat(outcomes).filteredOn(o -> o instanceof ReservationOutcome.Rejected)
				.extracting(o -> ((ReservationOutcome.Rejected) o).reason())
				.containsOnly(RejectionReason.SEATS_ALREADY_HELD);

		// The database-level confirmation that "granted" and "actually holding a seat, once each" are
		// the same fact: exactly SEAT_COUNT live claims exist for this show, one per seat, no seat
		// covered twice.
		Integer liveClaims = jdbcTemplate.queryForObject(
				"SELECT count(*) FROM reservation_seats WHERE show_id = ? AND released_at IS NULL",
				Integer.class, show.showId());
		assertThat(liveClaims).isEqualTo(SEAT_COUNT);

		Integer distinctSeatsHeld = jdbcTemplate.queryForObject(
				"SELECT count(DISTINCT seat_label) FROM reservation_seats WHERE show_id = ? AND released_at IS NULL",
				Integer.class, show.showId());
		assertThat(distinctSeatsHeld).isEqualTo(SEAT_COUNT);
	}

	/**
	 * Runs {@code task} on {@code count} virtual threads, released together by a single latch only
	 * after every one of them has reported ready — the "latch discipline" research.md R10 describes,
	 * so this test measures contention among threads that are genuinely all racing at once, not
	 * contention diluted by however long the last thread took to start up.
	 */
	static <T> List<T> runConcurrently(int count, java.util.function.IntFunction<T> task) throws InterruptedException {
		CountDownLatch ready = new CountDownLatch(count);
		CountDownLatch go = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(count);
		List<T> results = java.util.Collections.synchronizedList(new ArrayList<>(count));
		List<Thread> threads = new ArrayList<>(count);

		for (int i = 0; i < count; i++) {
			int index = i;
			threads.add(Thread.ofVirtual().start(() -> {
				ready.countDown();
				try {
					go.await();
					results.add(task.apply(index));
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				} finally {
					done.countDown();
				}
			}));
		}

		ready.await();
		go.countDown();
		boolean finished = done.await(60, TimeUnit.SECONDS);

		// WHY stragglers are interrupted rather than merely reported: a thread still blocked past the
		// deadline -- most plausibly still waiting on a JDBC connection -- does not stop existing just
		// because this method is about to return a failed assertion. Left alone, it keeps running in
		// the background and keeps holding whatever resource it was waiting on, competing with every
		// later test's own connections and threads for as long as it takes to eventually finish or
		// time out on its own. This was found, not assumed: an earlier version without this cleanup
		// left enough stragglers behind that SEPARATE test classes sharing this same pool started
		// failing with connection-acquisition errors that had nothing to do with their own logic.
		if (!finished) {
			for (Thread thread : threads) {
				if (thread.isAlive()) {
					thread.interrupt();
				}
			}
		}

		assertThat(finished).as("all %d requests to finish within 60s", count).isTrue();
		return results;
	}
}
