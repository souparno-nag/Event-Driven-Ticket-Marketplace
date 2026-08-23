package com.marketplace.orders.domain;

/**
 * Where an order sits in its lifecycle.
 *
 * <p>Only {@link #PENDING} is reachable in this build step, because nothing yet transitions an order
 * out of it — inventory-service and payment-service arrive in steps 3 and 4. All three constants are
 * declared now regardless. WHY: the database CHECK constraint in {@code V1__create_orders.sql} lists
 * the same three names, and declaring only the reachable one would mean the first confirmed order
 * fails that constraint at exactly the moment the saga starts working end to end.
 *
 * <p>Stored as its own name rather than its ordinal position. An ordinal is compact and silently
 * wrong the day someone inserts a constant in the middle of this list: every stored row then means
 * something different, with no error anywhere.
 */
public enum OrderStatus {

	/** Accepted and recorded. Seats are not held and no money has moved. The saga has not run. */
	PENDING,

	/** The saga completed: seats are held and payment succeeded. Terminal. */
	CONFIRMED,

	/** The saga was compensated — seats could not be held, or payment failed. Terminal. */
	CANCELLED
}
