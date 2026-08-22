package com.marketplace.events;

import static com.marketplace.events.Validation.requireMoney;
import static com.marketplace.events.Validation.requireNonEmptyDistinctSeats;
import static com.marketplace.events.Validation.requireNonNull;
import static com.marketplace.events.Validation.requireSagaMatchesOrder;
import static com.marketplace.events.Validation.requireSchemaVersion;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A customer has asked to buy specific seats. The first message of every saga.
 *
 * <p>Published by order-service after it has written the order row and this message's outbox row in
 * one transaction. Consumed by inventory-service, which attempts to hold the seats.
 *
 * <p>WHY it states a fact rather than issuing an instruction: nothing here tells inventory to
 * reserve anything. The order exists, in state {@code PENDING}; what any consumer does about that is
 * its own decision. That is what makes this choreography rather than orchestration, and it is why a
 * projection service can be added later to consume the same message with no change here.
 *
 * @param messageId     identity of this message; the consumer idempotency key
 * @param sagaId        correlates this order's messages; equals {@code orderId}
 * @param occurredAt    when the order was accepted
 * @param schemaVersion shape version of this message type
 * @param orderId       the order being created
 * @param userId        the customer placing it
 * @param showId        the concert being ticketed — never interchangeable with {@code messageId}
 * @param seatIds       the requested seats; non-empty, distinct, all-or-nothing
 * @param amount        the total to charge; non-negative, scale exactly 2
 */
public record OrderCreated(
		UUID messageId,
		UUID sagaId,
		Instant occurredAt,
		int schemaVersion,
		UUID orderId,
		UUID userId,
		UUID showId,
		List<String> seatIds,
		BigDecimal amount) implements SagaEvent {

	// A compact constructor: no parameter list, no assignments to fields. Java runs this first and
	// then assigns the (possibly reassigned) parameters, which is what lets the seat list be
	// swapped for a defensive copy before it is ever stored.
	public OrderCreated {
		requireNonNull(messageId, "messageId");
		requireSagaMatchesOrder(sagaId, orderId);
		requireNonNull(occurredAt, "occurredAt");
		requireSchemaVersion(schemaVersion);
		requireNonNull(userId, "userId");
		requireNonNull(showId, "showId");

		// Reassignment is the point: what gets stored is the unmodifiable copy, not the caller's
		// list. Without this line the caller keeps a live reference into a published message.
		seatIds = requireNonEmptyDistinctSeats(seatIds);
		amount = requireMoney(amount, "amount");
	}
}
