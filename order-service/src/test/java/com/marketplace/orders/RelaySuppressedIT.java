package com.marketplace.orders;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base for {@link PostgresIT} subclasses that need the background {@code OutboxRelay} poller
 * suppressed — which, in practice, is all of them.
 *
 * <p>WHY this exists as its own class rather than living directly on {@link PostgresIT}: it was tried
 * there first, and broke {@code OutboxRestartRecoveryIT} (see that class's own override and
 * {@code PostgresIT}'s javadoc for the full explanation) — Spring will not let a subclass reliably
 * undo a {@code @DynamicPropertySource} value its ancestor already set for the same key. Since
 * {@code OutboxRestartRecoveryIT} is unavoidably a descendant of {@code PostgresIT}, the suppression
 * has to live somewhere {@code OutboxRestartRecoveryIT} is NOT a descendant of — this class, which
 * only the tests that never need real scheduling extend.
 *
 * <p>WHY every plain {@code PostgresIT} subclass needs this, even ones that never call
 * {@code OutboxRelay} directly or even mention it: {@code @EnableScheduling} means the relay is
 * genuinely alive and polling in EVERY {@code @SpringBootTest} context this service builds, Kafka or
 * not. A class like {@code OrderApiIT} or {@code SchemaIT} never overrides
 * {@code spring.kafka.bootstrap-servers}, so its own relay's {@code KafkaTemplate} falls back to
 * whatever {@code application.yml} sets — {@code localhost:9092} — a real, independent broker with no
 * relationship to the ephemeral one {@code KafkaPostgresIT} starts for Phase 4. That relay is still
 * fully wired to the SAME shared {@code outbox} table every other context uses, and {@code claimBatch}
 * has no notion of "which test wrote this row" — it hands out whatever is {@code PENDING} to whichever
 * relay asks first. Left unsuppressed, a Phase 4 test inserting rows and racing its OWN dedicated
 * relay threads to publish them could lose some of those rows to a completely unrelated, still-cached
 * {@code PostgresIT}-only context's relay, which claims them, "successfully" sends them to the WRONG
 * broker, and marks them {@code PUBLISHED} — genuinely true, just not on the channel the Phase 4
 * test's own consumer is listening to. That row's message then never arrives no matter how long the
 * test waits, because it was never sent to the broker being watched.
 *
 * <p>Diagnosed by watching a Phase 4 test's own Kafka consumer sit with its position exactly equal to
 * the broker's end offset for far longer than its wait budget, while the database simultaneously
 * reported every one of that test's rows as {@code PUBLISHED} — proof the "missing" messages were
 * genuinely sent and acknowledged, just not to the broker anyone was watching. Confirmed directly:
 * producer connection logs from plain {@code PostgresIT}-only contexts (from tests like
 * {@code OrderApiIT}, {@code OrderCapacityIT}, {@code SchemaIT}) showed {@code bootstrap.servers =
 * [localhost:9092]} — the project's own long-lived local Kafka broker — running alongside the correct
 * ephemeral Testcontainers broker Phase 4 tests actually consume from.
 */
public abstract class RelaySuppressedIT extends PostgresIT {

	@DynamicPropertySource
	static void suppressBackgroundRelay(DynamicPropertyRegistry registry) {
		registry.add("outbox.relay.poll-interval-ms", () -> "3600000");
		registry.add("outbox.relay.initial-delay-ms", () -> "3600000");
	}
}
