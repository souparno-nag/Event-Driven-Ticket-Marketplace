package com.marketplace.orders.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.marketplace.orders.RelaySuppressedIT;

/**
 * Proves {@link Order}'s {@code @Version} column does what FR-022 requires: a concurrent update from
 * a stale copy is detected, not silently overwritten.
 *
 * <p>Depends only on classes that already exist ({@link Order}, {@link OrderRepository},
 * {@code PostgresIT}) — this is the one file in this batch that compiles and runs today, ahead of
 * the rest of User Story 1.
 *
 * <p>"Concurrent" is simulated by ordering, not by real threads: a copy is loaded and held (the
 * "loser"), then a separate transaction loads its own fresh copy, changes it, and commits (the
 * "winner"), bumping the version. Only then does the held copy attempt to save. This is deterministic
 * where real thread interleaving would not be, and exercises exactly the mechanism the version column
 * provides — a save whose {@code WHERE id = ? AND version = ?} matches no row, because the version
 * moved on without it.
 */
class OrderVersionIT extends RelaySuppressedIT {

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private PlatformTransactionManager transactionManager;

	@Test
	void aConcurrentUpdateFromAStaleCopyIsDetectedRatherThanSilentlyOverwritten() {
		TransactionTemplate tx = new TransactionTemplate(transactionManager);
		UUID orderId = UUID.randomUUID();

		tx.executeWithoutResult(status -> orderRepository.saveAndFlush(
				new Order(orderId, UUID.randomUUID(), UUID.randomUUID(), Set.of("A1"), new BigDecimal("10.00"))));

		// The loser's copy: read now, before the winner's update, so it carries version 0.
		Order staleCopy = tx.execute(status -> orderRepository.findById(orderId).orElseThrow());

		// The winner: its own fresh read, changed and committed. version 0 -> 1 in the database.
		tx.executeWithoutResult(status -> {
			Order fresh = orderRepository.findById(orderId).orElseThrow();
			fresh.changeStatus(OrderStatus.CONFIRMED);
			orderRepository.saveAndFlush(fresh);
		});

		// The loser tries to save the copy it has been holding, still at version 0. The database is
		// now at version 1, so this UPDATE matches no row -- which is the failure this column exists
		// to produce, rather than a silent overwrite of the winner's change.
		assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
			staleCopy.changeStatus(OrderStatus.CANCELLED);
			orderRepository.saveAndFlush(staleCopy);
		})).isInstanceOf(OptimisticLockingFailureException.class);

		// The winner's change survived; the loser never got to overwrite it.
		Order persisted = orderRepository.findById(orderId).orElseThrow();
		assertThat(persisted.getStatus()).isEqualTo(OrderStatus.CONFIRMED);
		assertThat(persisted.getVersion()).isEqualTo(1L);
	}
}
