package com.marketplace.inventory.seats;

import java.util.UUID;

/**
 * Builds the Redis key one seat hold lives under: {@code seat:{showId}:{seatId}}.
 *
 * <p>The brief's original format used {@code eventId} — that predates step 1's contract freeze,
 * where the field naming a concert was renamed to {@code showId} specifically because it had been
 * doing double duty with the message's own identity. Carrying the brief's spelling forward would key
 * a hold by message identity, which is unique per delivery: a redelivered {@code OrderCreated} would
 * then contend with nothing and take a second hold on a seat it already holds, and the mutual
 * exclusion this whole service exists to provide would be silently absent while every test that never
 * redelivers a message still passes (research.md R3, FR-007).
 *
 * <p>{@code common-events} makes the correct field checkable rather than a matter of care:
 * {@code OrderCreated} exposes {@code showId()} and {@code messageId()} as separate accessors of the
 * identical type, {@code UUID} — so this class exists as the one place that reads the right one,
 * rather than leaving every call site free to reach for either.
 *
 * <p>Deliberately a plain static utility, not a Spring bean — {@code SeatKeyTest} (T142) exercises it
 * with no application context at all, and a key builder that needed one would be a heavier dependency
 * than the one thing it does justifies. {@code inventory.hold.key-prefix} in {@code application.yml}
 * documents the first segment's value for readers of the configuration, but this class does not read
 * it: the format is a frozen part of the contract in {@code contracts/seat-lock-scripts.md}, not an
 * environment setting anything should vary.
 */
public final class SeatKey {

	private static final String PREFIX = "seat";

	private SeatKey() {
	}

	/**
	 * @param showId the show a seat belongs to — never a message's own identity
	 * @param seatId the seat label within that show
	 * @return {@code seat:{showId}:{seatId}}, stable for the same (showId, seatId) pair regardless
	 *         of which message, or how many redeliveries of it, asked for it
	 */
	public static String of(UUID showId, String seatId) {
		return PREFIX + ":" + showId + ":" + seatId;
	}
}
