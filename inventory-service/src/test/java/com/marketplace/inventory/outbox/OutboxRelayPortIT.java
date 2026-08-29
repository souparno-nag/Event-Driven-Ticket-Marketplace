package com.marketplace.inventory.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.marketplace.events.Topics;
import com.marketplace.inventory.InventoryKafkaIT;

/**
 * Proves the outbox relay ported from order-service in T130–T133 actually works in THIS module: a
 * pending row reaches its real channel, keyed by its saga id, and is marked {@code PUBLISHED} only
 * once the broker has it — using nothing this service wrote to read the result back.
 *
 * <p>TRADEOFF: this does not re-prove all twelve guarantees {@code contracts/outbox-relay.md} states.
 * That exhaustive suite already exists in order-service, against structurally identical code
 * (research.md R8) — re-proving every one of the twelve here would be duplicated effort rather than
 * added confidence. This test proves the port specifically: that the ported class, wired into THIS
 * service's own Spring context, against THIS service's own schema and THIS service's own broker
 * connection, genuinely does what it already does in order-service.
 *
 * <p>Deliberately waits for {@link OutboxRelay#pollAndPublish()} to fire ON ITS OWN SCHEDULE rather
 * than calling it directly — the stronger of the two choices, and not merely a style preference:
 * {@code LapsedReservationSweeper} (T161) is what first found that this service's scheduling
 * infrastructure had never actually been switched on (a missing {@code @EnableScheduling}), a gap that
 * survived undetected specifically because every earlier test exercising {@code OutboxRelay} called
 * its method directly. Waiting for the real timer here is what would have caught that same gap from
 * this side too.
 */
class OutboxRelayPortIT extends InventoryKafkaIT {

	@Autowired
	OutboxRepository outboxRepository;

	@Autowired
	JdbcTemplate jdbcTemplate;

	/**
	 * Clears every row this class does not own out of the shared {@code outbox} table before its own
	 * test runs.
	 *
	 * <p>Found necessary, not assumed up front: {@link com.marketplace.inventory.InventoryIT}'s
	 * {@code POSTGRES} container is a single real database shared by every IT class in the whole
	 * module (the same singleton-container reasoning order-service's own {@code PostgresIT}
	 * documents) — only the Spring CONTEXT is torn down between classes, never the rows already
	 * committed to that database. {@code ReservationService.decide(...)} writes an outbox row for
	 * EVERY booking attempt, granted or refused, and {@code ReservationContentionIT} alone drives a
	 * thousand attempts twenty times over. A full-suite run caught this directly: with that class run
	 * immediately before this one, a diagnostic count taken at the top of this test showed 19,700
	 * leftover {@code PENDING} rows already sitting in the table. {@code claimBatch}'s own contract is
	 * "always the earliest unsent row" (by design, so ordering per order is never violated) — so this
	 * test's own freshly-inserted row, carrying a higher id than every one of those 19,700, would not
	 * be claimed until the relay had drained the entire backlog ahead of it first. At 100 rows per
	 * 500ms poll that is roughly a hundred seconds, not the twenty this test budgets, which is why the
	 * failure looked identical to a slow scheduler no matter how far the timeout below was widened —
	 * the row was never being ignored, it was standing in a genuinely long, genuinely real queue.
	 *
	 * <p>Deleting unconditionally, not filtering to some marker distinguishing "another test's rows"
	 * from "this test's own leftover row from a previous run": this test always inserts exactly one
	 * row and asserts on it within the same method, so nothing of this test's own ever needs to
	 * survive across invocations, and any row present at start is, by construction, somebody else's.
	 */
	@BeforeEach
	void clearBacklogLeftBehindByOtherTests() {
		jdbcTemplate.update("DELETE FROM outbox");
	}

	@Test
	void pendingRowReachesItsChannelKeyedBySagaIdAndIsMarkedPublished() {
		UUID orderId = UUID.randomUUID();
		OutboxRecord record = new OutboxRecord(orderId, Topics.SEATS_RESERVED, "{\"seatIds\":[\"A1\"]}", null, null);
		outboxRepository.save(record);

		// 20s, not 10s: matches order-service's own OutboxRestartRecoveryIT precedent -- a
		// reasonable safety margin now that clearBacklogLeftBehindByOtherTests() above removes the
		// actual cause of the one failure this budget was ever seen to miss.
		await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
			OutboxRecord reloaded = outboxRepository.findById(record.getId()).orElseThrow();
			assertThat(reloaded.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
			assertThat(reloaded.getPublishedAt()).isNotNull();
		});

		var received = poll(Topics.SEATS_RESERVED, Duration.ofSeconds(10),
				collected -> collected.stream().anyMatch(r -> r.key().equals(orderId.toString())));

		// Not an exact string match against the payload as written: PostgreSQL's jsonb column
		// normalises its stored representation (a space after every colon, among other things), so
		// the bytes read back are never guaranteed byte-identical to the bytes written -- documented
		// already in V4__create_outbox.sql's own comments, and confirmed here rather than merely
		// trusted, since asserting exact-string equality against a jsonb round trip is exactly the
		// mistake that would otherwise make this test flaky for a reason that has nothing to do with
		// whether the relay actually works.
		assertThat(received)
				.anyMatch(r -> r.key().equals(orderId.toString()) && r.value().contains("A1"));
	}
}
