package com.marketplace.orders.outbox;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link OutboxRecord}.
 *
 * <p>Empty for now, and that is the whole content of this task. The interesting method —
 * {@code claimBatch}, which hands the relay a set of records it exclusively owns without breaking the
 * per-order ordering guarantee — arrives with the relay itself in build step T094. It cannot be
 * written as a derived query name, because what it does has no expression in Spring Data's method
 * vocabulary: it needs {@code FOR UPDATE SKIP LOCKED} and a correlated subquery, so it will be a
 * hand-written native query.
 *
 * <p>The identifier type is {@code Long} rather than {@code UUID}, unlike {@link OrderRepository}.
 * That is not an inconsistency: the outbox identifier is a monotonic sequence whose ORDER carries
 * meaning — it is the sequence messages must be published in — and a random UUID would carry none.
 */
public interface OutboxRepository extends JpaRepository<OutboxRecord, Long> {

	// Deliberately empty until T094.
}
