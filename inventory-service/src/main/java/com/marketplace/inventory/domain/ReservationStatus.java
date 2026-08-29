package com.marketplace.inventory.domain;

/**
 * Where a reservation sits in its lifecycle.
 *
 * <p>Only {@link #HELD} and {@link #EXPIRED} are reachable in this build step: a booking request
 * either succeeds and the reservation is held, or its hold lapses with nothing having confirmed the
 * order. {@link #COMMITTED} and {@link #RELEASED} are declared now regardless, matching the CHECK
 * constraint in {@code V2__create_reservations.sql} — step 4 fills in the transition to
 * {@code COMMITTED} and step 5 the transition to {@code RELEASED}, rather than migrating a live
 * table's constraint and every row already satisfying it the day those steps arrive.
 *
 * <p>Stored as its own name rather than its ordinal position, for the same reason as
 * {@code OrderStatus} in order-service: an ordinal is compact and silently wrong the moment a new
 * constant is inserted into the middle of this list, because every already-written row would then
 * mean something different with no error anywhere.
 *
 * <p>Liveness — whether a seat this reservation claims is still unavailable to anyone else — is
 * deliberately NOT derived from this status alone. See {@code ReservationSeat.releasedAt}: the two
 * live states, {@link #HELD} and {@link #COMMITTED}, both mean "claimed", and the two non-live
 * states, {@link #EXPIRED} and {@link #RELEASED}, both mean "free". A database constraint needs that
 * narrower fact on its own column, because it cannot be expressed as a comparison against the clock
 * (data-model.md, research.md R5).
 */
public enum ReservationStatus {

	/** A hold was granted and is either still live or has not yet been noticed as lapsed. */
	HELD,

	/**
	 * The hold's 120-second lifetime elapsed with nothing having confirmed the order. Reachable this
	 * build step: {@code ReservationService} moves a reservation here inline, the moment its seats
	 * are next contended for, rather than waiting on the periodic sweeper (FR-017, FR-018, R6).
	 */
	EXPIRED,

	/** The order was confirmed and the hold became permanent. Arrives with step 4. Terminal. */
	COMMITTED,

	/** The order was cancelled and the hold was released deliberately. Arrives with step 5. Terminal. */
	RELEASED
}
