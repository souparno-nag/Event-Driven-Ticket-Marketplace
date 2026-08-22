package com.marketplace.events;

/**
 * Why a charge attempt did not succeed, carried by {@code PaymentFailed}.
 *
 * <p>Unlike a seat rejection, a payment failure arrives when the saga has already taken a real
 * action — seats are held. It therefore always triggers compensation: the order moves to cancelled
 * and the holds are released.
 *
 * <p>The three values are separated by one question the order service genuinely needs answered:
 * <em>is it certain that no money moved?</em>
 */
public enum PaymentFailureReason {

	/**
	 * The provider gave a definitive "no" — insufficient funds, a blocked card, a failed fraud
	 * check.
	 *
	 * <p>WHY it is the safe case: a decline is an answer, so no money moved and none can move
	 * later for this attempt. Compensation can release the seats immediately with no reconciliation
	 * needed.
	 *
	 * <p>Retrying the identical charge is pointless — the same card with the same balance declines
	 * again. Recovery is a user action (a different card), which means a new order.
	 *
	 * <p>The simulated payment service in this project produces this value: it declines any amount
	 * whose value ends in 7 and approves everything else, which is enough to exercise the
	 * compensation path deterministically without a real provider.
	 */
	DECLINED,

	/**
	 * No answer arrived before the deadline, so the outcome of the charge is unknown.
	 *
	 * <p>WHY this is the dangerous one, and why it is not folded into {@link #PROVIDER_ERROR}: a
	 * timeout is an absence of information, not a failure. The request may have been lost on the
	 * way out, or it may have been fully processed and the response lost on the way back. The money
	 * may or may not have left the customer's account.
	 *
	 * <p>The consequence is that a blind retry can double-charge. Retrying is only safe when the
	 * request carries an idempotency key the provider recognises, so the second attempt is
	 * recognised as the same charge rather than a new one. That is why this project's retry policy
	 * is restricted to idempotent operations rather than applied to everything that failed.
	 *
	 * <p>The saga still cancels, because holding seats hostage to an unresolved charge is worse
	 * than releasing them; anything genuinely captured is a refund, settled outside the saga.
	 */
	TIMEOUT,

	/**
	 * The provider itself failed — a malformed or unparseable response, an internal error, an
	 * unreachable endpoint.
	 *
	 * <p>WHY it is distinct from {@link #DECLINED}: the customer's card was never the problem. This
	 * says the payment system is unwell, so many orders fail at once. It is the signal an operator
	 * looks for, and the one a circuit breaker acts on — a burst of declines is a normal Saturday,
	 * a burst of provider errors is an incident.
	 */
	PROVIDER_ERROR
	// TRADEOFF: no value carries the provider's own error code or message. A String field would
	// aid debugging, but it would also be untrusted third-party text copied into a message that is
	// stored, replayed, and shown to users — and it would tempt consumers to branch on the code
	// rather than on this enum, quietly coupling the saga to one provider's vocabulary. Detail
	// belongs in the payment service's own logs, correlated by the trace identifier that travels
	// in the message headers (FR-024).
}
