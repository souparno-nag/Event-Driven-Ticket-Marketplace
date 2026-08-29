package com.marketplace.inventory.outbox;

/**
 * Where an outbox record sits between being written and being sent.
 *
 * <p>Ported unchanged from order-service's {@code OutboxStatus} (research.md R8) — this is the
 * second outbox in the system, deliberately a copy rather than a shared module. See
 * {@code V4__create_outbox.sql} for why the mechanism itself needs no change here: the shape of
 * "written, sent, or given up on" is the same fact regardless of which service or which two message
 * types it is announcing.
 *
 * <p>WHY this exists at all, when {@code published_at IS NULL} would answer "is this outstanding?":
 * that test distinguishes two states and this table needs three. A record whose send fails
 * repeatedly must eventually stop being retried, and "not yet sent" and "will never be sent" are
 * different facts that demand different responses — one is patience, the other is an incident.
 *
 * <p>This enum is authoritative; {@code published_at} records the <em>time</em> of publication. A
 * CHECK constraint in {@code V4__create_outbox.sql} ties the two together so they cannot drift.
 */
public enum OutboxStatus {

	/** Written and awaiting the relay. The only state a newly recorded message is in. */
	PENDING,

	/** The broker acknowledged the message. Terminal. */
	PUBLISHED,

	/**
	 * Sending failed too many times and the relay has given up.
	 *
	 * <p>Terminal without human intervention, and deliberately consequential: because messages for
	 * one order must be sent in the order they were recorded, a parked record halts every later
	 * record for that same order. That is correct rather than unfortunate — publishing past it would
	 * tell consumers about a later fact before an earlier one — but it does mean a parked record is
	 * a stalled saga, which is why it is exposed as a metric rather than left in a table for someone
	 * to notice.
	 */
	PARKED
}
