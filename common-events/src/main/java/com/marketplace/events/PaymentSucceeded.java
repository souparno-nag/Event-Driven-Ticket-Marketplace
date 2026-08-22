package com.marketplace.events;

import static com.marketplace.events.Validation.requireMoney;
import static com.marketplace.events.Validation.requireNonNull;
import static com.marketplace.events.Validation.requireSagaMatchesOrder;
import static com.marketplace.events.Validation.requireSchemaVersion;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The customer has been charged. Published by payment-service, consumed by order-service, which
 * confirms the order.
 *
 * <p>This is the point of no return on the happy path: money has moved, so every later failure has
 * to be handled by refund and human intervention rather than by the saga.
 *
 * @param messageId     identity of this message
 * @param sagaId        equals {@code orderId}
 * @param occurredAt    when the charge succeeded
 * @param schemaVersion shape version of this message type
 * @param orderId       the order that was paid for
 * @param paymentId     the provider's identity for this charge — what a refund or a dispute is
 *                      raised against
 * @param amount        what was actually charged; non-negative, scale exactly 2
 */
public record PaymentSucceeded(
		UUID messageId,
		UUID sagaId,
		Instant occurredAt,
		int schemaVersion,
		UUID orderId,
		UUID paymentId,
		BigDecimal amount) implements SagaEvent {

	public PaymentSucceeded {
		requireNonNull(messageId, "messageId");
		requireSagaMatchesOrder(sagaId, orderId);
		requireNonNull(occurredAt, "occurredAt");
		requireSchemaVersion(schemaVersion);
		requireNonNull(paymentId, "paymentId");

		// WHY the amount is repeated from OrderCreated rather than assumed: this one is what was
		// actually taken, which is the only figure a reconciliation can trust. The contract cannot
		// enforce that the two agree — nothing here has sight of the OrderCreated message — so the
		// value of carrying it is precisely that a mismatch is visible afterwards.
		amount = requireMoney(amount, "amount");
	}
}
