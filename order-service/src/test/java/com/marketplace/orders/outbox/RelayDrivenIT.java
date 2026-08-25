package com.marketplace.orders.outbox;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.marketplace.orders.KafkaPostgresIT;

/**
 * Base for the Phase 4 tests that call {@code outboxRelay.pollAndPublish()} explicitly, rather than
 * relying on the background {@code @Scheduled} poller.
 *
 * <p>WHY this exists as its own shared class rather than each test declaring the same
 * {@code @DynamicPropertySource} override individually — which is how it was first written, and which
 * broke in a way worth recording: {@code @EnableScheduling} is genuinely active in every Phase 4
 * context, so without this override {@code pollAndPublish()} was ALSO firing automatically every
 * 500ms the whole time these tests were calling it directly — a real race, found by an intermittent
 * {@code statement_timeout} cancellation on a cleanup query blocked on a row the background run was
 * still processing.
 *
 * <p>The first fix declared the identical override separately on each of the four affected classes.
 * That passed every time Phase 4 ran on its own, and failed — reproducibly, not intermittently — the
 * moment it ran alongside Phase 3 in one build: {@code FATAL: sorry, too many clients already}.
 * Spring's test context cache keys a context by which class declared its configuration, including
 * {@code @DynamicPropertySource} methods; four classes each declaring their own meant four distinct
 * cached contexts, each eagerly opening its own 20-connection HikariCP pool. Declaring the override
 * exactly once, here, on a shared parent all four extend, is what lets them share one cached context
 * and one pool again — the same sharing every other sibling of {@link KafkaPostgresIT} already gets
 * for free.
 *
 * <p>This alone was not quite enough: even with this class collapsing four contexts into one, the
 * suite still runs several OTHER distinct contexts at once (plain {@code PostgresIT} subclasses,
 * {@code KafkaPostgresIT}'s own for {@code OutboxRestartRecoveryIT}, and the {@code @MockBean} nested
 * class in {@code OrderAcceptanceIT}), and that combination alone was still enough to exhaust the
 * connections available. See {@code PostgresIT.shrinkPoolForTests} for the fix that actually closed
 * this out — capping every test context's pool size, rather than trying to collapse every context
 * down to one, which is not possible here since {@code OutboxRestartRecoveryIT} genuinely needs a
 * different scheduling interval from the rest of Phase 4.
 */
abstract class RelayDrivenIT extends KafkaPostgresIT {

	@DynamicPropertySource
	static void suppressAutomaticScheduling(DynamicPropertyRegistry registry) {
		registry.add("outbox.relay.poll-interval-ms", () -> "3600000");
	}
}
