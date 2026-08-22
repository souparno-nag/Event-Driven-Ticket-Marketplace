package com.marketplace.events;

import static com.marketplace.events.Validation.requireNonNull;
import static com.marketplace.events.Validation.requireSagaMatchesOrder;
import static com.marketplace.events.Validation.requireSchemaVersion;

import java.time.Instant;
import java.util.UUID;

/**
 * The order ended without tickets. Published by order-service on every failure path; consumed by
 * inventory-service, which releases any hold it is carrying.
 *
 * <p>WHY one message covers all three causes rather than inventory reacting to {@code SeatsRejected}
 * and {@code PaymentFailed} separately: the order service owns the decision that an order is over,
 * and every other service reacts to that single decision. Letting each consumer interpret each
 * failure for itself would duplicate the same conclusion in every service, and they would drift.
 *
 * <p>WHY there is no seat list: the release is keyed by order, and on the {@code SEATS_UNAVAILABLE}
 * path no hold was ever taken, so there is no list to state. Inventory knows what it is holding for
 * an order; repeating it here would let a message disagree with the holder of record.
 *
 * @param messageId     identity of this message
 * @param sagaId        equals {@code orderId}
 * @param occurredAt    when the order was cancelled
 * @param schemaVersion shape version of this message type
 * @param orderId       the cancelled order
 * @param reason        which failure ended it — order-level vocabulary, deliberately coarser than
 *                      the step-level reason that caused it
 */
public record OrderCancelled(
		UUID messageId,
		UUID sagaId,
		Instant occurredAt,
		int schemaVersion,
		UUID orderId,
		CancellationReason reason) implements SagaEvent {

	public OrderCancelled {
		requireNonNull(messageId, "messageId");
		requireSagaMatchesOrder(sagaId, orderId);
		requireNonNull(occurredAt, "occurredAt");
		requireSchemaVersion(schemaVersion);

		// Required, because a cancelled order nobody can explain is the support ticket that cannot
		// be answered.
		requireNonNull(reason, "reason");
	}
}
