package com.marketplace.inventory.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.marketplace.events.RejectionReason;
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
 * across two classes would scatter one decision across two files for no benefit). The idempotency
 * guard that must run before this method is ever called in production arrives with User Story 3 —
 * every test exercising this class directly calls it exactly once per order, so no
 * redelivery-suppression is needed here yet.
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
			PlatformTransactionManager transactionManager,
			@Value("${inventory.hold.ttl-ms:120000}") long ttlMillis) {
		this.reservationRepository = reservationRepository;
		this.reservationSeatRepository = reservationSeatRepository;
		this.seatingPlanRepository = seatingPlanRepository;
		this.seatLockStore = seatLockStore;
		this.outboxWriter = outboxWriter;
		this.outboxRepository = outboxRepository;
		this.decisionMetrics = decisionMetrics;
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
		Instant decidedAt = Instant.now();

		ReservationOutcome outcome;
		try {
			outcome = transactionTemplate.execute(status -> decideAndRecord(orderId, showId, seatIds, decidedAt));
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
			outcome = transactionTemplate.execute(status -> decideAndRecord(orderId, showId, seatIds, decidedAt));
		}

		if (outcome instanceof ReservationOutcome.Reserved) {
			decisionMetrics.recordGranted();
		} else {
			decisionMetrics.recordRefused(((ReservationOutcome.Rejected) outcome).reason());
		}
		decisionMetrics.recordDecisionDuration(Duration.between(decidedAt, Instant.now()));

		return outcome;
	}

	/**
	 * Everything {@link #decide} does inside ONE transaction: decide the outcome, then record the
	 * outbox row announcing it. Kept separate from {@link #decide} itself so the retry-once logic
	 * there can run this exact unit of work a second time, in a second, independent transaction, on
	 * genuinely fresh state -- not merely re-execute a method call that happens to look the same.
	 */
	private ReservationOutcome decideAndRecord(UUID orderId, UUID showId, List<String> seatIds, Instant decidedAt) {
		ReservationOutcome outcome = decideOutcome(orderId, showId, seatIds, decidedAt);
		outboxRepository.save(outboxWriter.write(orderId, seatIds, outcome, decidedAt));
		return outcome;
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
