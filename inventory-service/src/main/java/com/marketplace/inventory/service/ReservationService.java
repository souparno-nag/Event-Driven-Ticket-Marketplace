package com.marketplace.inventory.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.marketplace.events.RejectionReason;
import com.marketplace.inventory.consume.IdempotencyGuard;
import com.marketplace.inventory.domain.Reservation;
import com.marketplace.inventory.domain.ReservationRepository;
import com.marketplace.inventory.domain.ReservationSeat;
import com.marketplace.inventory.domain.ReservationSeatRepository;
import com.marketplace.inventory.domain.SeatingPlanRepository;
import com.marketplace.inventory.outbox.OutboxRepository;
import com.marketplace.inventory.outbox.OutboxWriter;
import com.marketplace.inventory.seats.SeatLockStore;

/**
 * Decides a booking request and records everything that follows from that decision, in one
 * transaction — or, on a detected optimistic-lock collision, in one fresh transaction retried exactly
 * once (see {@link #decide}'s own Javadoc for why that retry cannot simply reuse the transaction that
 * just failed).
 *
 * <p>{@link #decideAndRecord} is deliberately the ONLY place {@code reservations},
 * {@code reservation_seats}, and {@code outbox} are all written from this service — the same
 * discipline order-service's own {@code OrderAcceptanceService} applies to its own two tables. FR-025
 * requires the decided outcome and the seat state it was decided against to be genuinely atomic; the
 * only way to make that claim reviewable, rather than merely hoped for, is to have exactly one method
 * where every row involved is written, inside one transactional boundary.
 *
 * <p>THIS BUILD STEP'S SCOPE, stated plainly: this class now decides between all three outcomes User
 * Story 1 and User Story 2 together name — every seat granted, or refused as one of
 * {@link RejectionReason#SHOW_NOT_FOUND}, {@link RejectionReason#SEATS_NOT_FOUND}, or
 * {@link RejectionReason#SEATS_ALREADY_HELD} — via the same method rather than a second one
 * (tasks.md's own note on why these two stories are not fully independent: splitting one decision
 * across two classes would scatter one decision across two files for no benefit).
 *
 * <p>User Story 3 adds the idempotency guard via a SEPARATE, four-argument {@link #decide(UUID, UUID,
 * UUID, List)} overload rather than changing the original three-argument signature every User Story 1
 * and User Story 2 test already calls directly: those tests call this class exactly once per order by
 * construction (their whole point is exercising the decision logic itself, never redelivery), so
 * threading a {@code messageId} through them would ask every one of those call sites to invent an
 * identity that means nothing to what they are actually testing. {@code OrderCreatedListener} (T178),
 * the one real caller in production, uses the four-argument form exclusively.
 */
@Service
public class ReservationService {

	private final ReservationRepository reservationRepository;
	private final ReservationSeatRepository reservationSeatRepository;
	private final SeatingPlanRepository seatingPlanRepository;
	private final SeatLockStore seatLockStore;
	private final OutboxWriter outboxWriter;
	private final OutboxRepository outboxRepository;
	private final DecisionMetrics decisionMetrics;
	private final IdempotencyGuard idempotencyGuard;
	private final TransactionTemplate transactionTemplate;
	private final long ttlMillis;

	public ReservationService(
			ReservationRepository reservationRepository,
			ReservationSeatRepository reservationSeatRepository,
			SeatingPlanRepository seatingPlanRepository,
			SeatLockStore seatLockStore,
			OutboxWriter outboxWriter,
			OutboxRepository outboxRepository,
			DecisionMetrics decisionMetrics,
			IdempotencyGuard idempotencyGuard,
			PlatformTransactionManager transactionManager,
			@Value("${inventory.hold.ttl-ms:120000}") long ttlMillis) {
		this.reservationRepository = reservationRepository;
		this.reservationSeatRepository = reservationSeatRepository;
		this.seatingPlanRepository = seatingPlanRepository;
		this.seatLockStore = seatLockStore;
		this.outboxWriter = outboxWriter;
		this.outboxRepository = outboxRepository;
		this.decisionMetrics = decisionMetrics;
		this.idempotencyGuard = idempotencyGuard;
		this.transactionTemplate = new TransactionTemplate(transactionManager);
		this.ttlMillis = ttlMillis;
	}

	/**
	 * Decides whether {@code seatIds} in {@code showId} can be held for {@code orderId}, and records
	 * every consequence of that decision.
	 *
	 * <p>Ordering within this method is load-bearing, not incidental (contracts/inventory-consumer.md,
	 * FR-033):
	 *
	 * <ol>
	 *   <li>Does the show exist at all? If not, refuse as {@link RejectionReason#SHOW_NOT_FOUND}
	 *       before examining a single seat — the failure is one level up from the seating chart, and
	 *       nothing about the requested seats has even been evaluated yet.
	 *   <li>Do every one of the requested seat labels exist within that show's plan? If not, refuse as
	 *       {@link RejectionReason#SEATS_NOT_FOUND} — deliberately a different cause from the one
	 *       below, because this one never succeeds on retry and the other very well might.
	 *   <li>Retire any lapsed reservation covering these exact seats, in THIS transaction (FR-018,
	 *       research.md R6) — Redis frees a seat the instant its TTL lapses, but the old reservation
	 *       is still {@code HELD} in PostgreSQL until this step says otherwise. Skipping it would let
	 *       {@code ux_reservation_seat_live} reject a booking Redis just legitimately granted.
	 *   <li>Attempt the atomic Redis hold. This is inside the transaction but not part of it —
	 *       {@code SeatLockStore}'s own Javadoc explains why that direction of inconsistency is the
	 *       accepted one.
	 *   <li>Record the reservation and its seats ONLY if the hold succeeded — a refusal, for ANY of the
	 *       three causes, writes no reservation row at all (data-model.md).
	 *   <li>Record the outbox row announcing whichever outcome this was, in the same transaction as
	 *       everything above, so the announcement can never be lost between commit and publish nor
	 *       recomputed later against seat state that has moved on (FR-025). The outbox row always
	 *       carries the FULL requested seat set, for every cause including a refusal — {@code
	 *       OutboxWriter} never filters it down to just the seats that caused the trouble, which is
	 *       what lets a caller always see exactly what it asked for (FR-023).
	 * </ol>
	 *
	 * @param orderId the saga id this decision belongs to
	 * @param showId  the show the requested seats belong to
	 * @param seatIds the seats requested, all-or-nothing
	 * @return what was decided — never null, exactly one outcome per call (FR-022)
	 */
	public ReservationOutcome decide(UUID orderId, UUID showId, List<String> seatIds) {
		// messageId is null: every caller of this three-argument form is a test exercising the
		// decision logic directly, exactly once per order (see this class's own Javadoc) -- there is
		// no message identity to guard against a redelivery of, because nothing here is a redelivery.
		return decideWithRetry(null, orderId, showId, seatIds).orElseThrow(() -> new IllegalStateException(
				"decide(orderId, showId, seatIds) must always produce an outcome when messageId is null"));
	}

	/**
	 * The real entry point {@code OrderCreatedListener} (T178) calls in production: identical to the
	 * three-argument {@link #decide(UUID, UUID, List)}, except the FIRST thing it does, inside the
	 * SAME transaction as everything else, is ask {@link IdempotencyGuard#isFirstDelivery(UUID)}
	 * whether {@code messageId} has been seen before (contracts/inventory-consumer.md step 4;
	 * CLAUDE.md requirement 3).
	 *
	 * <p>WHY this guard has to run before step 1 of {@link #decideOutcome}, not merely before the Redis
	 * hold: a redelivered message must produce NO further effect at all, including re-evaluating
	 * whether the show or seats still exist — those checks are cheap and harmless to repeat, but
	 * running them on a redelivery is still work this method has no reason to do twice, and keeping
	 * the guard as the single first thing this method does keeps that fact obviously true by
	 * inspection rather than by tracing every branch below it.
	 *
	 * @return the decided outcome, or {@link Optional#empty()} if {@code messageId} has already been
	 *         processed by this consumer — in which case nothing further happened and there is
	 *         nothing new to report (FR-030)
	 */
	public Optional<ReservationOutcome> decide(UUID messageId, UUID orderId, UUID showId, List<String> seatIds) {
		return decideWithRetry(messageId, orderId, showId, seatIds);
	}

	/**
	 * The retry-once wrapper shared by both {@link #decide} overloads, and the one place metrics are
	 * recorded — exactly once per genuinely NEW decision, never for a redelivery {@code decideAndRecord}
	 * recognised and skipped, which would otherwise double-count a single logical decision across two
	 * deliveries.
	 */
	private Optional<ReservationOutcome> decideWithRetry(
			UUID messageId, UUID orderId, UUID showId, List<String> seatIds) {
		Instant decidedAt = Instant.now();

		Optional<ReservationOutcome> outcome;
		try {
			outcome = transactionTemplate.execute(
					status -> decideAndRecord(messageId, orderId, showId, seatIds, decidedAt));
		} catch (OptimisticLockingFailureException retryOnce) {
			// Retry exactly once, per CLAUDE.md's own optimistic-concurrency requirement -- and in a
			// BRAND NEW transaction, not the one that just failed. Found necessary directly, not
			// assumed: this method used to be a single @Transactional method, and two orders racing to
			// retire the same lapsed reservation (FR-018) would occasionally have the loser's flush
			// throw ObjectOptimisticLockingFailureException with nothing anywhere catching it --
			// ReservationVersionIT (T148) caught this the moment its own timing made the two threads'
			// retirement attempts land close enough together to collide for real. Catching the
			// exception INSIDE the same @Transactional method would not have been enough on its own:
			// once Hibernate raises a StaleObjectStateException during a flush, the persistence context
			// that experienced it is not safe to keep using for further work in that same transaction.
			// TransactionTemplate.execute(...) is what gives the retry a genuinely fresh transaction and
			// a genuinely fresh persistence context to load the now-current row into, rather than
			// re-reading stale state through a session that has already seen this exact conflict once.
			outcome = transactionTemplate.execute(
					status -> decideAndRecord(messageId, orderId, showId, seatIds, decidedAt));
		}

		outcome.ifPresent(decided -> {
			if (decided instanceof ReservationOutcome.Reserved) {
				decisionMetrics.recordGranted();
			} else {
				decisionMetrics.recordRefused(((ReservationOutcome.Rejected) decided).reason());
			}
			decisionMetrics.recordDecisionDuration(Duration.between(decidedAt, Instant.now()));
		});

		return outcome;
	}

	/**
	 * Everything one delivery does inside ONE transaction: check idempotency (only when
	 * {@code messageId} is not null), decide the outcome, then record the outbox row announcing it.
	 * Kept separate from {@link #decideWithRetry} so the retry-once logic there can run this exact
	 * unit of work a second time, in a second, independent transaction, on genuinely fresh state --
	 * not merely re-execute a method call that happens to look the same.
	 */
	private Optional<ReservationOutcome> decideAndRecord(
			UUID messageId, UUID orderId, UUID showId, List<String> seatIds, Instant decidedAt) {
		if (messageId != null && !idempotencyGuard.isFirstDelivery(messageId)) {
			return Optional.empty();
		}

		ReservationOutcome outcome = decideOutcome(orderId, showId, seatIds, decidedAt);
		outboxRepository.save(outboxWriter.write(orderId, seatIds, outcome, decidedAt));
		return Optional.of(outcome);
	}

	/**
	 * The three seating-plan-and-contention checks in the order FR-033 requires, stopping at the
	 * first one that fails. Split out from {@link #decide} so that method's own Javadoc can describe
	 * the six-step sequence as a whole without this method's internal early-returns interrupting it.
	 */
	private ReservationOutcome decideOutcome(UUID orderId, UUID showId, List<String> seatIds, Instant decidedAt) {
		if (!seatingPlanRepository.existsById(showId)) {
			return new ReservationOutcome.Rejected(RejectionReason.SHOW_NOT_FOUND);
		}

		// Compares counts, exactly as SeatingPlanRepository's own Javadoc describes: if they differ,
		// at least one requested label does not exist. Requests never carry a duplicate seat label in
		// practice (a buyer books each seat at most once), so this stays a plain size comparison rather
		// than a set-difference -- the simpler check that is actually true of every real caller.
		Set<String> existingLabels = seatingPlanRepository.findExistingSeatLabels(showId, seatIds);
		if (existingLabels.size() != seatIds.size()) {
			return new ReservationOutcome.Rejected(RejectionReason.SEATS_NOT_FOUND);
		}

		retireLapsedReservationsCovering(showId, seatIds, decidedAt);

		if (seatLockStore.tryLock(showId, seatIds, orderId)) {
			return recordReservation(orderId, showId, seatIds, decidedAt);
		}
		return new ReservationOutcome.Rejected(RejectionReason.SEATS_ALREADY_HELD);
	}

	/**
	 * FR-018's inline retirement. A reservation's hold covers every seat it claims with one shared
	 * expiry, so once ANY of its seats is found lapsed the whole reservation is retired — not merely
	 * the one contended seat — mirroring how the hold itself was always all-or-nothing.
	 *
	 * <p>Mutating the loaded {@link Reservation} and {@link ReservationSeat} entities directly, and
	 * relying on JPA's own dirty checking to persist the change at commit, is what puts this update
	 * through the identical {@code @Version} check {@code ReservationVersionIT} (T148) exercises —
	 * two callers racing to retire the same stale reservation collide here exactly once, and the
	 * loser is detected rather than silently overwritten.
	 */
	private void retireLapsedReservationsCovering(UUID showId, List<String> seatIds, Instant asOf) {
		for (Reservation lapsed : reservationRepository.findLapsedReservationsCoveringSeats(showId, seatIds, asOf)) {
			lapsed.expire();
			for (ReservationSeat seat : reservationSeatRepository.findByIdReservationId(lapsed.getReservationId())) {
				seat.release(asOf);
			}
		}

		// Found directly, not assumed: without this flush, ux_reservation_seat_live rejected a
		// booking Redis had just legitimately granted, on a seat this very method had just retired.
		// Hibernate's write-behind queue does not flush in the order statements were issued -- by
		// default it groups ALL pending inserts ahead of ALL pending updates, regardless of which was
		// dirtied first. recordReservation's new ReservationSeat is an insert; the release() call
		// above is an update on an already-loaded row. Left to Hibernate's own ordering, the new
		// seat's insert would reach PostgreSQL before this retirement's UPDATE clears released_at on
		// the old row, so the partial unique index -- scoped to released_at IS NULL -- would still see
		// two live claims on the same seat and refuse the second one. Flushing here forces the
		// retirement to hit the database before recordReservation ever runs, so the two can never race
		// against each other within this one transaction.
		reservationSeatRepository.flush();
		reservationRepository.flush();
	}

	private ReservationOutcome recordReservation(UUID orderId, UUID showId, List<String> seatIds, Instant decidedAt) {
		UUID reservationId = UUID.randomUUID();
		Instant lockExpiresAt = decidedAt.plusMillis(ttlMillis);

		reservationRepository.save(new Reservation(reservationId, orderId, showId, lockExpiresAt));
		for (String seatId : seatIds) {
			reservationSeatRepository.save(new ReservationSeat(reservationId, seatId, showId));
		}

		return new ReservationOutcome.Reserved(reservationId, lockExpiresAt);
	}
}
