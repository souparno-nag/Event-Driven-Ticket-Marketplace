package com.marketplace.inventory.consume;

import java.util.UUID;

import org.springframework.stereotype.Component;

/**
 * The sole mechanism by which this service's at-least-once delivery is made safe (CLAUDE.md
 * requirement 3; FR-028, FR-032; contracts/inventory-consumer.md). This is the one piece of this
 * build step left for the developer to write by hand — everything around it, including
 * {@link ProcessedMessage}, {@link ProcessedMessageRepository}, and the class that calls this one
 * ({@code OrderCreatedListener}, T178), ships working. A beginner-level guide to arriving at a
 * correct body lives in {@code docs/tasks/T174-idempotency-guard-guide.md}; read that before writing
 * the method below.
 *
 * <p>CONTRACT (contracts/inventory-consumer.md, step 4 of "the order of operations"):
 *
 * <ol>
 *   <li>Attempt to insert a {@link ProcessedMessage} row for {@code (messageId, "inventory-service")}
 *       — in the SAME transaction as everything else this delivery is about to do, not a separate one.
 *   <li>If the insert succeeds, this is the first time this consumer has seen this message: return
 *       {@code true}, and the caller proceeds with the rest of the decision.
 *   <li>If the insert fails with a duplicate-key violation, this message has already been fully
 *       handled by this consumer: return {@code false}. The caller must do nothing further — no
 *       second hold attempt, no second reservation, no second outbox row — and let the surrounding
 *       transaction commit (or simply do nothing, since nothing else was written).
 * </ol>
 *
 * <p>THE ORDERING THIS GUARD EXISTS TO PROTECT, restated because it is the single easiest mistake to
 * make here: this check MUST run before the Redis hold is ever attempted, not after. Run it after, and
 * a redelivery calls {@code SeatLockStore.tryLock(...)} a second time with the SAME order id — which
 * the script's own guarantee 3 treats as re-acquiring seats this order already holds, and returns
 * success. The redelivery would then silently attempt to write a SECOND {@code reservations} row for
 * an order that already has one, tripping the {@code order_id UNIQUE} constraint at best, or racing
 * the first delivery's own still-open transaction at worst — neither of which is "already handled,
 * skip", which is the only correct response to a redelivery. Running this guard FIRST is what turns a
 * redelivery into a no-op before any of that has a chance to happen.
 *
 * <p>GUARANTEES THIS METHOD MUST SATISFY ({@code IdempotencyIT}, T168):
 * <ol>
 *   <li>{@code tenDeliveriesOneEffect}
 *   <li>{@code distinctMessagesAreIndependent}
 *   <li>{@code outcomeSurvivesInterruption}
 * </ol>
 */
@Component
public class IdempotencyGuard {

	/** Matches {@code spring.kafka.consumer.group-id} — see that property's own comment in
	 * {@code application.yml} for why the two must never drift apart. */
	static final String CONSUMER_NAME = "inventory-service";

	private final ProcessedMessageRepository processedMessageRepository;

	public IdempotencyGuard(ProcessedMessageRepository processedMessageRepository) {
		this.processedMessageRepository = processedMessageRepository;
	}

	/**
	 * @param messageId the envelope {@code messageId} of the message currently being processed —
	 *                  never the order id, and never the saga id
	 * @return {@code true} if this is the first time this consumer has seen this message and the
	 *         caller should proceed; {@code false} if it has already been handled and the caller
	 *         must do nothing further
	 */
	public boolean isFirstDelivery(UUID messageId) {
		// TODO(developer): see docs/tasks/T174-idempotency-guard-guide.md before writing this.
		//
		// Contract, restated in code terms: attempt processedMessageRepository.save(new
		// ProcessedMessage(messageId, CONSUMER_NAME)) inside the CALLER's transaction (this method
		// takes no @Transactional of its own — the transaction boundary belongs on the service
		// method, per contracts/inventory-consumer.md's own note on why @Transactional belongs on
		// the service, not the listener). Catch DataIntegrityViolationException specifically, not
		// Exception generally, and return false from that catch block. Let every other exception
		// propagate uncaught -- a database that is merely unreachable must look like exactly that,
		// not like "message already processed".
		throw new UnsupportedOperationException(
				"IdempotencyGuard.isFirstDelivery is a developer exercise (T174) -- see "
						+ "docs/tasks/T174-idempotency-guard-guide.md and contracts/inventory-consumer.md");
	}
}
