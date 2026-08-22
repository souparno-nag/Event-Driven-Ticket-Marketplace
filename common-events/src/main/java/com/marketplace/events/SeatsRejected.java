package com.marketplace.events;

import static com.marketplace.events.Validation.requireNonEmptyDistinctSeats;
import static com.marketplace.events.Validation.requireNonNull;
import static com.marketplace.events.Validation.requireSagaMatchesOrder;
import static com.marketplace.events.Validation.requireSchemaVersion;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The seats could not be held, so none were. Published by inventory-service, consumed by
 * order-service, which cancels the order immediately.
 *
 * <p>WHY this path has nothing to compensate: the hold is all-or-nothing, so a rejection means no
 * seat was ever taken and no money was ever moved. The cancellation that follows is bookkeeping
 * rather than an undo, which makes this the shortest route through the saga.
 *
 * @param messageId     identity of this message
 * @param sagaId        equals {@code orderId}
 * @param occurredAt    when the attempt failed
 * @param schemaVersion shape version of this message type
 * @param orderId       the order that was refused
 * @param seatIds       the seats that were requested — reported back in full, not just the
 *                      unavailable ones, because the request was refused as a unit
 * @param reason        why, as an enum rather than prose (FR-009)
 */
public record SeatsRejected(
		UUID messageId,
		UUID sagaId,
		Instant occurredAt,
		int schemaVersion,
		UUID orderId,
		List<String> seatIds,
		RejectionReason reason) implements SagaEvent {

	public SeatsRejected {
		requireNonNull(messageId, "messageId");
		requireSagaMatchesOrder(sagaId, orderId);
		requireNonNull(occurredAt, "occurredAt");
		requireSchemaVersion(schemaVersion);
		seatIds = requireNonEmptyDistinctSeats(seatIds);

		// A rejection with no stated cause is the one message nobody can act on or explain later,
		// so the enum is required rather than nullable.
		requireNonNull(reason, "reason");
	}
}
