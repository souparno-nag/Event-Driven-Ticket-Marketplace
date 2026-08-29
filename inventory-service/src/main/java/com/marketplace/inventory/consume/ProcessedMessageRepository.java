package com.marketplace.inventory.consume;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link ProcessedMessage}.
 *
 * <p>Deliberately empty, matching order-service's own {@code OrderRepository}: the guard's whole job
 * is one {@code save()} inside the caller's transaction and catching the constraint violation that
 * means "already handled" — there is no query this interface needs to add. {@code existsById} is
 * available if a caller ever needs to check without inserting, but the guard contract
 * (contracts/inventory-consumer.md) calls for attempting the insert directly rather than checking
 * first and inserting second, which would open a window between the check and the insert for two
 * concurrent redeliveries to both see "not yet processed."
 */
public interface ProcessedMessageRepository extends JpaRepository<ProcessedMessage, ProcessedMessageId> {
}
