package com.marketplace.orders.outbox;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.marketplace.orders.KafkaPostgresIT;

/**
 * Base for the Phase 4 tests that call {@code outboxRelay.pollAndPublish()} explicitly, rather than
 * relying on the background {@code @Scheduled} poller.
 *
 * <p>WHY this suppression is declared here, on a class specific to these four tests, rather than on
 * {@code KafkaPostgresIT} itself (which would cover them with less machinery): {@code KafkaPostgresIT}
 * is also the parent of {@code OutboxRestartRecoveryIT}, which needs the OPPOSITE — real scheduling —
 * to prove automatic recovery. Spring will not let a subclass reliably override a
 * {@code @DynamicPropertySource} value an ancestor already registered for the same key, so the
 * suppression cannot live anywhere {@code OutboxRestartRecoveryIT} would inherit it from. This class
 * exists so the four tests that want it can share one declaration without dragging
 * {@code OutboxRestartRecoveryIT} in — the same reasoning behind {@code RelaySuppressedIT} on the
 * Postgres-only side, and the reasoning is written out in full on {@code PostgresIT} itself.
 *
 * <p>WHY the suppression is needed at all: {@code @EnableScheduling} is genuinely active in every
 * Phase 4 context, so without it {@code pollAndPublish()} was ALSO firing automatically in the
 * background the whole time these tests were calling it directly — a real race, first found by an
 * intermittent {@code statement_timeout} cancellation on a cleanup query blocked on a row the
 * background run was still processing.
 *
 * <p>WHY both {@code poll-interval-ms} AND {@code initial-delay-ms} are overridden, not just the
 * interval: {@code @Scheduled(fixedDelayString = ...)} only bounds the gap BETWEEN runs — its very
 * first run fires close to immediately after the context starts, regardless of how large the interval
 * is. Overriding only the interval left that first, uncontrolled run free to race these tests exactly
 * as before; see {@code OutboxRelay.pollAndPublish}'s own javadoc for where this was actually diagnosed
 * (a Mockito stubbing race in an unrelated test, {@code OrderAcceptanceIT$RollbackWhenTheOutboxWriteFails}).
 *
 * <p>WHY declaring this once here, rather than separately on each of the four classes, matters beyond
 * tidiness: declaring the same override on several different classes gives each one its OWN cached
 * Spring context (Spring's test-context cache keys on which class declares a
 * {@code @DynamicPropertySource}), and several such contexts alive at once was, in an earlier version
 * of this fix, enough to exhaust the shared PostgreSQL container's connection limit when the whole
 * suite ran together. Declaring it exactly once here, on a shared parent all four extend, keeps them
 * sharing one cached context and one connection pool.
 */
abstract class RelayDrivenIT extends KafkaPostgresIT {

	@DynamicPropertySource
	static void suppressAutomaticScheduling(DynamicPropertyRegistry registry) {
		registry.add("outbox.relay.poll-interval-ms", () -> "3600000");
		registry.add("outbox.relay.initial-delay-ms", () -> "3600000");
	}
}
