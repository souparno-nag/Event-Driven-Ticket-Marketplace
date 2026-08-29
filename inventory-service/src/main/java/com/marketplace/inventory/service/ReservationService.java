package com.marketplace.inventory.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.marketplace.events.RejectionReason;
import com.marketplace.inventory.domain.Reservation;
import com.marketplace.inventory.domain.ReservationRepository;
import com.marketplace.inventory.domain.ReservationSeat;
import com.marketplace.inventory.domain.ReservationSeatRepository;
import com.marketplace.inventory.outbox.OutboxRepository;
import com.marketplace.inventory.outbox.OutboxWriter;
import com.marketplace.inventory.seats.SeatLockStore;

/**
 * Decides a booking request and records everything that follows from that decision, in one
 * transaction.
 *
 * <p>{@link #decide} is deliberately the ONLY place {@code reservations}, {@code reservation_seats},
 * and {@code outbox} are all written from this service — the same discipline order-service's own
 * {@code OrderAcceptanceService} applies to its own two tables. FR-025 requires the decided outcome
 * and the seat state it was decided against to be genuinely atomic; the only way to make that claim
 * reviewable, rather than merely hoped for, is to have exactly one method where every row involved is
 * written, inside one {@code @Transactional} boundary.
 *
 * <p>THIS BUILD STEP'S SCOPE, stated plainly: this class currently decides between exactly two
 * outcomes — every seat granted, or refused as {@link RejectionReason#SEATS_ALREADY_HELD}. The
 * seating-plan causes — {@code SHOW_NOT_FOUND}, {@code SEATS_NOT_FOUND} — arrive with User Story 2,
 * which extends this same method rather than introducing a second one (tasks.md's own note on why
 * these two stories are not fully independent: splitting one decision across two classes would
 * scatter one decision across two files for no benefit). The idempotency guard that must run before
 * this method is ever called in production arrives with User Story 3 — every test exercising this
 * class directly in User Story 1 calls it exactly once per order, so no redelivery-suppression is
 * needed here yet.
 */
@Service
public class ReservationService {

	private final ReservationRepository reservationRepository;
	private final ReservationSeatRepository reservationSeatRepository;
	private final SeatLockStore seatLockStore;
	private final OutboxWriter outboxWriter;
	private final OutboxRepository outboxRepository;
	private final long ttlMillis;

	public ReservationService(
			ReservationRepository reservationRepository,
			ReservationSeatRepository reservationSeatRepository,
			SeatLockStore seatLockStore,
			OutboxWriter outboxWriter,
			OutboxRepository outboxRepository,
			@Value("${inventory.hold.ttl-ms:120000}") long ttlMillis) {
		this.reservationRepository = reservationRepository;
		this.reservationSeatRepository = reservationSeatRepository;
		this.seatLockStore = seatLockStore;
		this.outboxWriter = outboxWriter;
		this.outboxRepository = outboxRepository;
		this.ttlMillis = ttlMillis;
	}

	/**
	 * Decides whether {@code seatIds} in {@code showId} can be held for {@code orderId}, and records
	 * every consequence of that decision.
	 *
	 * <p>Ordering within this method is load-bearing, not incidental (contracts/inventory-consumer.md):
	 *
	 * <ol>
	 *   <li>Retire any lapsed reservation covering these exact seats, in THIS transaction (FR-018,
	 *       research.md R6) — Redis frees a seat the instant its TTL lapses, but the old reservation
	 *       is still {@code HELD} in PostgreSQL until this step says otherwise. Skipping it would let
	 *       {@code ux_reservation_seat_live} reject a booking Redis just legitimately granted.
	 *   <li>Attempt the atomic Redis hold. This is inside the transaction but not part of it —
	 *       {@code SeatLockStore}'s own Javadoc explains why that direction of inconsistency is the
	 *       accepted one.
	 *   <li>Record the reservation and its seats ONLY if the hold succeeded — a refusal writes no
	 *       reservation row at all (data-model.md).
	 *   <li>Record the outbox row announcing whichever outcome this was, in the same transaction as
	 *       everything above, so the announcement can never be lost between commit and publish nor
	 *       recomputed later against seat state that has moved on (FR-025).
	 * </ol>
	 *
	 * @param orderId the saga id this decision belongs to
	 * @param showId  the show the requested seats belong to
	 * @param seatIds the seats requested, all-or-nothing
	 * @return what was decided — never null, exactly one outcome per call (FR-022)
	 */
	@Transactional
	public ReservationOutcome decide(UUID orderId, UUID showId, List<String> seatIds) {
		Instant decidedAt = Instant.now();

		retireLapsedReservationsCovering(showId, seatIds, decidedAt);

		ReservationOutcome outcome;
		if (seatLockStore.tryLock(showId, seatIds, orderId)) {
			outcome = recordReservation(orderId, showId, seatIds, decidedAt);
		} else {
			outcome = new ReservationOutcome.Rejected(RejectionReason.SEATS_ALREADY_HELD);
		}

		outboxRepository.save(outboxWriter.write(orderId, seatIds, outcome, decidedAt));
		return outcome;
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
