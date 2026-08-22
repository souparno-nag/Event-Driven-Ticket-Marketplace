package com.marketplace.events;

import java.time.Instant;
import java.util.UUID;

/**
 * Everything true of every saga message, and nothing else.
 *
 * <p>This declares the four envelope accessors and no state, no behaviour, and no default methods.
 * It is not a base type in the usual sense — records cannot extend a class, so there is no
 * inheritance available here even if it were wanted (R2). What it buys is a single type a consumer
 * can {@code switch} over.
 *
 * <p>Its purpose is exhaustiveness. Once this interface is sealed, the compiler knows the complete
 * set of message types, so a {@code switch} covering all of them needs no {@code default} branch —
 * and adding an eighth message type later turns every such {@code switch} into a compile error
 * listing exactly what has not been handled. Without sealing, that same omission is a
 * {@code default} branch silently ignoring a message at runtime.
 *
 * <p>SEALING IS COMPLETED IN T026, once the seven records exist. Java requires every type named in
 * a {@code permits} clause to be compiled alongside the interface, so the clause cannot be written
 * before its subtypes — the declaration would not compile, and neither would anything else in the
 * module. The intended final form, from data-model.md, is:
 *
 * <pre>{@code
 * public sealed interface SagaEvent
 *         permits OrderCreated, SeatsReserved, SeatsRejected,
 *                 PaymentSucceeded, PaymentFailed, OrderConfirmed, OrderCancelled {
 * }</pre>
 *
 * <p>Until then the interface is open, which is strictly weaker but never wrong: the records added
 * in Phase 3 implement it either way, and T026 only narrows who else may.
 */
public interface SagaEvent {

	/**
	 * Identity of <em>this message</em>, unique and never reused — not even when a failed publish
	 * is retried, since a republished message is a second delivery attempt and consumers must be
	 * able to tell one delivery from a genuinely new fact.
	 *
	 * <p>WHY it matters beyond identification: from step 2 this is the idempotency key. A consumer
	 * records the ids it has processed and skips repeats, which is what makes at-least-once
	 * delivery safe — a broker that may deliver the same message twice is harmless if the second
	 * delivery changes nothing.
	 *
	 * <p>WHY the name is not {@code eventId}: the original brief used that one word for both this
	 * and the concert being ticketed, so a call site could pass either where the other was meant and
	 * still compile. The concert is {@code showId}; the word "event" is avoided as a field name
	 * anywhere in this module (FR-003).
	 */
	UUID messageId();

	/**
	 * Correlates every message belonging to one order's lifecycle. Always equal to that order's
	 * {@code orderId}.
	 *
	 * <p>WHY it is a separate field despite always equalling {@code orderId}: it is the partition
	 * key, and giving it a name that says <em>correlation</em> rather than <em>the order</em> keeps
	 * the two roles distinct at the call site. Keying by it confines one order's messages to a
	 * single partition, which is what preserves their relative order without any consumer having to
	 * reorder anything (FR-026).
	 */
	UUID sagaId();

	/**
	 * When the fact happened — not when it was published, and not when it was consumed.
	 *
	 * <p>WHY the distinction is worth the care: a message can sit in a channel through a consumer
	 * outage and be handled minutes late. Anything reasoning about elapsed time — the step-4 check
	 * of whether a seat hold has lapsed, most obviously — needs the moment of the fact, because
	 * publish and consume times are properties of the plumbing rather than of the business.
	 */
	Instant occurredAt();

	/**
	 * The version of this message type's shape. Starts at 1, incremented only on a breaking change.
	 *
	 * <p>WHY a version travels on every message rather than being inferred from the channel: during
	 * any rolling deployment, producers and consumers run different builds simultaneously, so a
	 * consumer will meet a shape it was not compiled against. Carrying the number lets it recognise
	 * that rather than misread the payload.
	 *
	 * <p>A consumer meeting a version it does not recognise MUST NOT process the message, and routes
	 * it to that type's dead-letter channel instead. Discarding it is prohibited: these messages
	 * move money and seat inventory, and a lost one strands a saga with no record of why (FR-023).
	 */
	int schemaVersion();
}
