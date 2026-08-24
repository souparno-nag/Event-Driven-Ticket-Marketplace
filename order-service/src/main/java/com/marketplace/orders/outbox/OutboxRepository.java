package com.marketplace.orders.outbox;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for {@link OutboxRecord}.
 *
 * <p>The identifier type is {@code Long} rather than {@code UUID}, unlike {@link OrderRepository}.
 * That is not an inconsistency: the outbox identifier is a monotonic sequence whose ORDER carries
 * meaning — it is the sequence messages must be published in — and a random UUID would carry none.
 */
public interface OutboxRepository extends JpaRepository<OutboxRecord, Long> {

	/**
	 * Hands the caller up to {@code limit} rows it now owns exclusively for the rest of its
	 * transaction — one PENDING row per order at most, always the earliest unsent one, never a row
	 * belonging to an order that already has an earlier row parked.
	 *
	 * <p>WHY ordering lives HERE, in the predicate, rather than in any coordination between relays:
	 * the {@code MIN(id)} subquery makes a later row for one order structurally invisible to every
	 * relay until the earlier row leaves {@code PENDING}. That holds no matter how many relays are
	 * running and no matter what order they happen to poll in — the guarantee is a property of what
	 * this query returns, not of any agreement between callers about how to behave. A relay author
	 * cannot get FR-014 wrong by writing the surrounding Java code carelessly; the only way to break
	 * it is to violate what this method promises (see {@code contracts/outbox-relay.md}).
	 *
	 * <p>The {@code NOT EXISTS} clause is what implements FR-030. Without it, {@code MIN(id)} would
	 * step straight over a {@code PARKED} row — which is not {@code PENDING} and so does not affect
	 * the minimum on its own — and hand out the row behind it, publishing a later fact before an
	 * earlier one that never made it out. This is the exact bug the parking decision exists to
	 * prevent, and it lives in one line here rather than as a check the relay's own code has to
	 * remember to make.
	 *
	 * <p>{@code FOR UPDATE SKIP LOCKED} claims exclusivity (FR-012, FR-013): a row already locked by
	 * another transaction is skipped rather than waited for, so a relay with nothing left to claim
	 * proceeds immediately instead of queuing behind whichever relay got there first. Only rows this
	 * outer query actually returns are locked — the correlated subqueries reference the same table by
	 * different aliases but are not themselves part of what {@code FOR UPDATE} locks, which is
	 * ordinary PostgreSQL locking semantics, not something special arranged here.
	 *
	 * <p>TRADEOFF: a {@code pg_try_advisory_xact_lock(hashtext(aggregate_id))} per order was
	 * considered and rejected. It would let more than one row per order be claimed in a single poll,
	 * which this query deliberately does not allow — but it introduces a lock namespace shared across
	 * the whole PostgreSQL instance, and a {@code hashtext} collision between two unrelated aggregate
	 * ids would silently serialise two orders that have nothing to do with each other. This query's
	 * "one row per order per poll" limit costs almost nothing in practice — an order accumulates at
	 * most a handful of outbox rows across its entire life, seconds apart — and buys a guarantee that
	 * cannot degrade under a hash collision nobody would notice.
	 *
	 * @param limit the maximum number of rows to claim in this call (FR-015) — bounds the transaction
	 *              so a large backlog drains across several polls rather than in one unbounded unit
	 */
	@Query(value = """
			SELECT *
			FROM   outbox o
			WHERE  o.status = 'PENDING'
			  AND  o.id = (SELECT MIN(i.id) FROM outbox i
			               WHERE i.aggregate_id = o.aggregate_id AND i.status = 'PENDING')
			  AND  NOT EXISTS (SELECT 1 FROM outbox p
			                   WHERE p.aggregate_id = o.aggregate_id
			                     AND p.status = 'PARKED'
			                     AND p.id < o.id)
			ORDER BY o.id
			LIMIT  :limit
			FOR UPDATE SKIP LOCKED
			""", nativeQuery = true)
	List<OutboxRecord> claimBatch(@Param("limit") int limit);
}
