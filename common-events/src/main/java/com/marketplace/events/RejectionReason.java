package com.marketplace.events;

/**
 * Why an attempt to hold seats failed, carried by {@code SeatsRejected}.
 *
 * <p>A rejection ends the saga: the order is cancelled immediately, with no payment attempted and
 * nothing to compensate. The reason therefore does not select a recovery path — it explains the
 * outcome to the user and to whoever is reading the logs at 3am.
 */
public enum RejectionReason {

	/**
	 * At least one requested seat is currently held by a different order.
	 *
	 * <p>WHY this is the only *contended* reason: seat holds are all-or-nothing, so a request for
	 * five seats where one is taken rejects all five. The caller cannot tell which seat lost, and
	 * deliberately is not told — naming it would invite a client to retry seat-by-seat and turn a
	 * clean atomic operation into a race.
	 *
	 * <p>This is the retryable case in practice: holds expire, so the same request may well succeed
	 * a minute later. The contract does not promise that, and no automatic retry exists.
	 */
	SEATS_ALREADY_HELD,

	/**
	 * The show exists, but at least one requested seat label does not belong to it.
	 *
	 * <p>WHY it is separate from {@link #SEATS_ALREADY_HELD}: this one never succeeds on retry. Seat
	 * "Z99" in a hall whose rows stop at M is a bad request, not bad luck, and treating the two the
	 * same would have clients retrying forever against a seat that will never exist.
	 */
	SEATS_NOT_FOUND,

	/**
	 * No show matches the requested identifier, so its seat map cannot be consulted at all.
	 *
	 * <p>WHY it is distinct rather than folded into {@link #SEATS_NOT_FOUND}: the failure is one
	 * level up. Nothing about the seats was even evaluated, which points at a stale client or a
	 * mistyped identifier rather than at the seating chart.
	 */
	SHOW_NOT_FOUND
	// TRADEOFF: an enum rather than a free-text String field (FR-009). Prose would let the
	// inventory service phrase the same cause three different ways and force every consumer to
	// pattern-match on wording that nobody promised to keep stable. The cost is that adding a
	// cause later means a contract change consumed by every service — accepted, because a fixed
	// set is exactly what makes the value safe to branch on.
}
