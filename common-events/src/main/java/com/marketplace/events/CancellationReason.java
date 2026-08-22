package com.marketplace.events;

/**
 * Why an order ended without tickets, carried by {@code OrderCancelled}.
 *
 * <p>{@code OrderCancelled} is the compensation trigger: whatever the cause, inventory releases the
 * seat holds when it arrives. So these values do not select a recovery path — they record which
 * upstream event drove the order into its terminal state, which is what makes a cancelled order
 * explainable months later without replaying the whole saga.
 *
 * <p>WHY this is a separate enum rather than a reuse of {@link RejectionReason} or
 * {@link PaymentFailureReason}: those describe why one <em>step</em> failed, and are owned by the
 * services that publish them. This describes why the <em>order</em> ended, and is owned by the
 * order service. Collapsing them would leak inventory's vocabulary into an order-level fact, and
 * would grow this contract every time either upstream service added a cause of its own.
 */
public enum CancellationReason {

	/**
	 * The charge did not succeed, so the held seats were released.
	 *
	 * <p>Note the deliberate loss of detail: every {@code PaymentFailed} collapses to this one
	 * value, whether the card was declined or the provider timed out. That distinction matters to
	 * whoever decides about retrying or refunding, and it is preserved on the {@code PaymentFailed}
	 * message. It does not matter to the seats, which are released identically either way.
	 */
	PAYMENT_FAILED,

	/**
	 * The seats could not be held in the first place, so the order was cancelled immediately.
	 *
	 * <p>WHY there is nothing to compensate here: this follows {@code SeatsRejected}, and a
	 * rejection is all-or-nothing — no hold was ever taken. The cancellation is bookkeeping, closing
	 * the order rather than undoing anything. It is the shortest path through the saga.
	 */
	SEATS_UNAVAILABLE,

	/**
	 * The seat hold lapsed before the saga resolved, so the reservation could no longer be honoured.
	 *
	 * <p>WHY this value exists before anything emits it: holds expire on a timer, so a saga that
	 * stalls long enough may find its seats legitimately resold to someone else. Build step 4 has to
	 * compare {@code SeatsReserved.lockExpiresAt} against the current time before confirming, and
	 * cancel with this reason when the hold has lapsed — the fencing check that stops a stalled saga
	 * confirming a seat another customer now holds.
	 *
	 * <p>It is declared now rather than added then because these constant names are part of the
	 * serialized form. Adding a value later means a message a deployed consumer cannot deserialize,
	 * so it would force a schema version bump across every service already reading this channel.
	 * Declaring one known-needed value up front costs nothing; retrofitting it costs a coordinated
	 * redeploy.
	 *
	 * <p>TRADEOFF: this is speculative in the sense the project's rules normally reject — a value
	 * with no caller. Accepted only because the requirement is confirmed rather than imagined
	 * (data-model.md names the step-4 fencing check) and because the cost of deferring is a breaking
	 * change rather than an ordinary edit. That reasoning does not extend to speculative
	 * <em>code</em>: a value in a published vocabulary is not the same commitment as an abstraction
	 * nobody calls.
	 */
	RESERVATION_EXPIRED
}
