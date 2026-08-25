package com.marketplace.orders.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.marketplace.events.Topics;

/**
 * Specifies guarantee 11 of {@code contracts/outbox-relay.md}: two — here, three — relays running
 * concurrently against one store never send the same row twice (FR-012, FR-013, SC-006).
 *
 * <p>Will not compile until {@code OutboxRelay} exists (T097).
 *
 * <p>WHY three threads calling the same bean's method rather than three separate application
 * instances: {@code pollAndPublish()} is {@code @Transactional}, and Spring gives each concurrent
 * invocation its own transaction regardless of which thread calls it or how many bean instances exist.
 * The property under test — whether the claim query lets two transactions see the same row at once —
 * is entirely about the database, not about how many JVMs are asking it questions. Three real
 * application instances would exercise the identical database behaviour at a much higher cost to
 * start.
 *
 * <p>Extends {@link RelayDrivenIT} — see that class for why these tests need the background scheduler
 * suppressed, and why the suppression lives on one shared class rather than here.
 */
class OutboxConcurrencyIT extends RelayDrivenIT {

	private static final int ROW_COUNT = 1000;
	private static final int RELAY_THREADS = 3;

	@Autowired
	private OutboxRelay outboxRelay;

	@Autowired
	private OutboxRepository outboxRepository;

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void noRecordSentTwice() throws Exception {
		List<Long> ids = new ArrayList<>(ROW_COUNT);
		Set<String> myKeys = new HashSet<>();
		for (int i = 0; i < ROW_COUNT; i++) {
			UUID aggregateId = UUID.randomUUID();
			OutboxRecord record = new OutboxRecord(aggregateId, Topics.ORDER_CREATED, "{\"n\":" + i + "}", null, null);
			ids.add(outboxRepository.save(record).getId());
			myKeys.add(aggregateId.toString());
		}

		ExecutorService pool = Executors.newFixedThreadPool(RELAY_THREADS);
		try {
			List<Future<?>> futures = new ArrayList<>();
			for (int i = 0; i < RELAY_THREADS; i++) {
				futures.add(pool.submit(this::drainUntilEmpty));
			}
			for (Future<?> future : futures) {
				future.get(90, TimeUnit.SECONDS);
			}
		} finally {
			pool.shutdown();
		}

		List<OutboxRecord> saved = outboxRepository.findAllById(ids);
		assertThat(saved).as("every row ends up PUBLISHED").allSatisfy(
				r -> assertThat(r.getStatus()).isEqualTo(OutboxStatus.PUBLISHED));

		// Filtered to exactly the keys THIS test created, and waited-for by that same filtered count --
		// the channel is shared with every other Phase 4 test class in this run, so a plain "wait for
		// 1000 records" could be satisfied early by a mix of other tests' messages and this test's own,
		// without ever actually confirming all 1000 of these specific rows arrived.
		List<ConsumerRecord<String, String>> consumed = poll(Topics.ORDER_CREATED, Duration.ofSeconds(60),
				collected -> collected.stream().filter(r -> myKeys.contains(r.key())).count() >= ROW_COUNT);

		Map<String, Long> countsByKey = consumed.stream()
				.filter(r -> myKeys.contains(r.key()))
				.collect(Collectors.groupingBy(ConsumerRecord::key, Collectors.counting()));

		// Anti-vacuity: the count-per-key assertion below would also pass if half the rows never
		// arrived at all, since "count == 1" says nothing about rows that show up zero times.
		assertThat(countsByKey).as("every one of the %d rows this test created was seen", ROW_COUNT)
				.hasSize(ROW_COUNT);

		// The direct assertion this guarantee is about: no key was ever sent more than once, no
		// matter how many relays raced to claim rows at the same moment.
		assertThat(countsByKey.values())
				.as("no row's message appears on the channel more than once")
				.allSatisfy(count -> assertThat(count).isEqualTo(1L));
	}

	/** Repeatedly claims and sends a batch until nothing PENDING remains. One worker's share of the
	 * three-way race this test is about. */
	private void drainUntilEmpty() {
		while (pendingCount() > 0) {
			outboxRelay.pollAndPublish();
		}
	}

	private long pendingCount() {
		Long count = jdbc.queryForObject("SELECT count(*) FROM outbox WHERE status = 'PENDING'", Long.class);
		return count == null ? 0 : count;
	}
}
