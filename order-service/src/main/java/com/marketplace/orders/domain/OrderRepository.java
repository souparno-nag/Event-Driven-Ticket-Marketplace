package com.marketplace.orders.domain;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for the {@link Order} aggregate.
 *
 * <p>There is no implementation of this interface anywhere, and there is not meant to be. Spring
 * Data generates a proxy at startup from {@code JpaRepository}, which supplies {@code save},
 * {@code findById}, {@code delete}, and the rest.
 *
 * <p>WHY there is no hand-written interface wrapping this one: a {@code OrderStore} abstraction over
 * Spring Data would exist to allow swapping the persistence technology, and nothing here is going to
 * swap it — the outbox claim query in build step 3 depends on PostgreSQL row locking specifically.
 * An abstraction whose only justification is a change that will never happen is the kind the project
 * constitution asks to be left out.
 *
 * <p>The type parameters are the entity and the type of its identifier: {@code UUID}, assigned by the
 * application rather than the database.
 */
public interface OrderRepository extends JpaRepository<Order, UUID> {

	// Deliberately empty. Reading an order by its id is findById, inherited. Query methods arrive
	// only when a real caller needs one.
}
