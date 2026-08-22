package com.marketplace.events;

import static com.marketplace.events.Validation.requireNonNull;
import static com.marketplace.events.Validation.requireSagaMatchesOrder;
import static com.marketplace.events.Validation.requireSchemaVersion;

import java.time.Instant;
import java.util.UUID;

/**
 * The charge did not succeed. Published by payment-service, consumed by order-service, which
 * cancels the order and thereby releases the seats.
 *
 * <p>WHY this message carries no amount: nothing was taken, or — on a timeout — nobody knows what
 * was taken. Stating a figure would assert a fact the publisher does not have. The amount at stake
 * is on the {@code OrderCreated} that started the saga, where it is a request rather than a claim
 * about money that moved.
 *
 * @param messageId     identity of this message
 * @param sagaId        equals {@code orderId}
 * @param occurredAt    when the attempt failed
 * @param schemaVersion shape version of this message type
 * @param orderId       the order whose payment failed
 * @param reason        why, as an enum — and specifically whether it is certain no money moved
 */
public record PaymentFailed(
		UUID messageId,
		UUID sagaId,
		Instant occurredAt,
		int schemaVersion,
		UUID orderId,
		PaymentFailureReason reason) implements SagaEvent {

	public PaymentFailed {
		requireNonNull(messageId, "messageId");
		requireSagaMatchesOrder(sagaId, orderId);
		requireNonNull(occurredAt, "occurredAt");
		requireSchemaVersion(schemaVersion);
		requireNonNull(reason, "reason");
	}
}
