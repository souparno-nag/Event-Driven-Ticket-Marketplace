package com.marketplace.events;

import static com.marketplace.events.Validation.requireNonEmptyDistinctSeats;
import static com.marketplace.events.Validation.requireNonNull;
import static com.marketplace.events.Validation.requireSagaMatchesOrder;
import static com.marketplace.events.Validation.requireSchemaVersion;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Every requested seat is now held for this order. Published by inventory-service, consumed by
 * payment-service.
 *
 * <p>The hold is all-or-nothing: {@code seatIds} is exactly the set requested, never a subset. A
 * partial hold would leave a customer paying for fewer seats than they chose, so the alternative
 * outcome is {@code SeatsRejected} for the whole request.
 *
 * @param messageId     identity of this message
 * @param sagaId        equals {@code orderId}
 * @param occurredAt    when the seats were held
 * @param schemaVersion shape version of this message type
 * @param orderId       the order the seats are held for
 * @param seatIds       exactly the seats requested
 * @param reservationId identity of the durable reservation, for the later commit or release
 * @param lockExpiresAt when the hold lapses; strictly after {@code occurredAt}
 */
public record SeatsReserved(
		UUID messageId,
		UUID sagaId,
		Instant occurredAt,
		int schemaVersion,
		UUID orderId,
		List<String> seatIds,
		UUID reservationId,
		Instant lockExpiresAt) implements SagaEvent {

	public SeatsReserved {
		requireNonNull(messageId, "messageId");
		requireSagaMatchesOrder(sagaId, orderId);
		requireNonNull(occurredAt, "occurredAt");
		requireSchemaVersion(schemaVersion);
		requireNonNull(reservationId, "reservationId");
		seatIds = requireNonEmptyDistinctSeats(seatIds);

		// WHY this rule lives here and not in Validation: it is the only invariant relating two
		// components of one record, so it has no meaning outside this type. A helper taking two
		// Instants would be reusable and would say nothing about what it is for.
		requireNonNull(lockExpiresAt, "lockExpiresAt");
		if (!lockExpiresAt.isAfter(occurredAt)) {
			// STRICTLY after, not merely not-before. A hold expiring at the instant it was taken is
			// already expired, so the step-4 fencing check could never pass for such a message —
			// requiring strict inequality makes that state impossible to construct rather than
			// merely unlikely.
			throw new IllegalArgumentException(
					"lockExpiresAt must be strictly after occurredAt, but lockExpiresAt=" + lockExpiresAt
							+ " and occurredAt=" + occurredAt);
		}
	}
}
