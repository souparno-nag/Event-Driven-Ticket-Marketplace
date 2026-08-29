package com.marketplace.inventory.service;

import java.time.Instant;
import java.util.UUID;

import com.marketplace.events.RejectionReason;

/**
 * What a single booking decision produced: every requested seat held, or none of them.
 *
 * <p>Sealed with exactly two cases — {@link Reserved} and {@link Rejected} — which is what makes the
 * switch that maps an outcome to a message ({@code OutboxWriter}, T159) exhaustive by construction.
 * Adding a third outcome kind later is a compile error at every such switch, not a silently unhandled
 * branch discovered the day it first happens in production. {@code OutcomeMappingTest} (T150) is
 * where that exhaustiveness is actually exercised.
 *
 * <p>There is no third case for "the decision could not be made" — an undecidable request (the stores
 * are unreachable, FR-047) produces no {@code ReservationOutcome} at all. It fails the message's
 * consumption instead, so the message is redelivered rather than answered with an outcome that was
 * never really decided.
 */
public sealed interface ReservationOutcome {

	/**
	 * Every requested seat is now held.
	 *
	 * @param reservationId the durable reservation's own identity — announced on {@code SeatsReserved}
	 *                      so a later step can name which reservation to commit or release
	 * @param lockExpiresAt the exact moment stored on the reservation's own row, threaded through
	 *                      rather than recomputed at message-write time. {@code OutboxWriter} reads
	 *                      this value directly for the outgoing message, so the announced lapse
	 *                      moment and the database's own {@code lock_expires_at} are the identical
	 *                      value by construction — not two independent computations from the same TTL
	 *                      constant that happen to agree today and could silently drift apart later
	 */
	record Reserved(UUID reservationId, Instant lockExpiresAt) implements ReservationOutcome {
	}

	/**
	 * None of the requested seats were held — the hold is all-or-nothing, so a rejection means no
	 * seat was ever taken.
	 *
	 * @param reason one of the three frozen causes (FR-023). Never a fourth meaning "could not
	 *               decide" — see this interface's own note on why that case does not exist here at
	 *               all.
	 */
	record Rejected(RejectionReason reason) implements ReservationOutcome {
	}
}
