package com.marketplace.events;

import static com.marketplace.events.Validation.requireNonEmptyDistinctSeats;
import static com.marketplace.events.Validation.requireNonNull;
import static com.marketplace.events.Validation.requireSagaMatchesOrder;
import static com.marketplace.events.Validation.requireSchemaVersion;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * The order is complete and the seats belong to the customer. Published by order-service; consumed
 * by inventory-service, which commits the reservation from a temporary hold to a durable one, and
 * by projection-service, which updates the read model.
 *
 * <p>A terminal, absorbing state: nothing follows it and nothing reopens it.
 *
 * <p>WHY the seats are repeated here rather than left for consumers to recall from
 * {@code SeatsReserved}: a consumer that joined later, or replayed only this channel, must be able
 * to act on this message alone. Requiring a consumer to have seen an earlier message to interpret
 * this one would make the channel useless for anything but a live, in-order, never-restarted reader.
 *
 * @param messageId     identity of this message
 * @param sagaId        equals {@code orderId}
 * @param occurredAt    when the order was confirmed
 * @param schemaVersion shape version of this message type
 * @param orderId       the confirmed order
 * @param seatIds       the seats now owned by the customer
 */
public record OrderConfirmed(
		UUID messageId,
		UUID sagaId,
		Instant occurredAt,
		int schemaVersion,
		UUID orderId,
		List<String> seatIds) implements SagaEvent {

	public OrderConfirmed {
		requireNonNull(messageId, "messageId");
		requireSagaMatchesOrder(sagaId, orderId);
		requireNonNull(occurredAt, "occurredAt");
		requireSchemaVersion(schemaVersion);
		seatIds = requireNonEmptyDistinctSeats(seatIds);
	}
}
