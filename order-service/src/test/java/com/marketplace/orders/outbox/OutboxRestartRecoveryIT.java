package com.marketplace.orders.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.awaitility.Awaitility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.marketplace.events.Topics;
import com.marketplace.orders.KafkaPostgresIT;

/**
 * Specifies FR-019 / SC-005: outbox rows left over from a stopped service are sent automatically once
 * the relay is running again, with no manual step.
 *
 * <p>WHY this test needs nothing that does not already exist — no reference to {@code OutboxRelay}
 * appears anywhere in this file. That is deliberate, not an oversight: recovery is not a separate
 * feature the developer implements in T099. It falls out of the relay having no in-memory memory of
 * its own — every run asks the database "what is PENDING right now?" and the answer to that question
 * does not care whether the service asking it has been running for an hour or was started ten seconds
 * ago. Rows inserted here, before this test ever touches a relay bean directly, are indistinguishable
 * from rows a previous instance of the service left behind when it stopped.
 *
 * <p>Consequently this file compiles today. Whether it PASSES depends on {@code @EnableScheduling}
 * and a working {@code pollAndPublish()} existing (T097, T099) — until then, {@link Awaitility}'s
 * bounded wait times out with an honest failure naming exactly what never happened, the same "compiles
 * now, fails cleanly until the implementation lands" pattern used for {@code OrderApiIT} in the
 * previous batch.
 *
 * <p>WHY this class extends {@code KafkaPostgresIT} directly rather than {@code RelayDrivenIT}: it is
 * the one test in the whole suite that genuinely needs the OPPOSITE of what every other Phase 4 class
 * wants — real, automatic scheduling, at production speed, with no test-driven call to
 * {@code pollAndPublish()} anywhere. Neither {@code KafkaPostgresIT} nor {@code PostgresIT} overrides
 * the relay's scheduling properties, so this class simply gets the real defaults with no override of
 * its own needed — see {@code RelayDrivenIT} and {@code RelaySuppressedIT} for where suppression lives
 * for everyone else, and why it could not live on an ancestor of this class instead.
 */
class OutboxRestartRecoveryIT extends KafkaPostgresIT {

	private static final int ROW_COUNT = 5;

	@Autowired
	private OutboxRepository outboxRepository;

	@Test
	void outstandingRecordsAreSentAutomaticallyWithNoManualStep() {
		List<Long> ids = new ArrayList<>(ROW_COUNT);
		for (int i = 0; i < ROW_COUNT; i++) {
			OutboxRecord record = new OutboxRecord(
					UUID.randomUUID(), Topics.ORDER_CREATED, "{\"marker\":\"restart-recovery-" + i + "\"}", null, null);
			ids.add(outboxRepository.save(record).getId());
		}

		// No call to any relay method anywhere in this test. Only @Scheduled, running on its own
		// timer, can be what moves these rows to PUBLISHED -- which is the entire point: nothing here
		// simulates a restart being noticed and handled. The rows are simply present, and something
		// must find them without being told to.
		Awaitility.await()
				.atMost(Duration.ofSeconds(20))
				.pollInterval(Duration.ofMillis(250))
				.untilAsserted(() -> {
					List<OutboxRecord> reloaded = outboxRepository.findAllById(ids);
					assertThat(reloaded).hasSize(ROW_COUNT);
					assertThat(reloaded).as("every outstanding row reaches PUBLISHED with no manual step")
							.allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(OutboxStatus.PUBLISHED));
				});
	}
}
