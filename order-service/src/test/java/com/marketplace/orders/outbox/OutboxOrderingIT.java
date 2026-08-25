package com.marketplace.orders.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketplace.events.Topics;

/**
 * Specifies guarantee 12 of {@code contracts/outbox-relay.md} — rows for one order reach the channel
 * in the order they were recorded, even while several relays run concurrently — plus the property
 * SC-013 asks for alongside it: a {@code PARKED} row halts only its own order.
 *
 * <p>Will not compile until {@code OutboxRelay} exists (T097).
 *
 * <p>WHY this is largely a test of the claim query (T094) rather than of anything the developer
 * writes in T099: {@code contracts/outbox-relay.md} says as much directly — the ordering guarantee
 * lives in the predicate the claim query uses to select rows, not in the relay method's own logic.
 * This test exercises that guarantee end to end, through the whole relay, rather than by inspecting
 * the query's SQL in isolation.
 *
 * <p>Extends {@link RelayDrivenIT} — see that class for why these tests need the background scheduler
 * suppressed, and why the suppression lives on one shared class rather than here.
 */
class OutboxOrderingIT extends RelayDrivenIT {

	private static final int ORDER_COUNT = 100;
	private static final int ROWS_PER_ORDER = 3;
	private static final int RELAY_THREADS = 3;

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Autowired
	private OutboxRelay outboxRelay;

	@Autowired
	private OutboxRepository outboxRepository;

	@Autowired
	private JdbcTemplate jdbc;

	@Value("${outbox.relay.max-attempts:5}")
	private int maxAttempts;

	@Test
	void preservesPerOrderOrder() throws Exception {
		Map<UUID, List<Long>> idsByOrder = new LinkedHashMap<>();

		for (int order = 0; order < ORDER_COUNT; order++) {
			UUID aggregateId = UUID.randomUUID();
			List<Long> ids = new ArrayList<>();
			for (int seq = 0; seq < ROWS_PER_ORDER; seq++) {
				String payload = "{\"seq\":" + seq + "}";
				ids.add(outboxRepository.save(
						new OutboxRecord(aggregateId, Topics.ORDER_CREATED, payload, null, null)).getId());
			}
			idsByOrder.put(aggregateId, ids);
		}

		runConcurrentlyUntilDrained();

		// The channel is shared with every other Phase 4 test class in this same run, so filtering to
		// exactly the keys THIS test created -- before parsing anything -- is what keeps an unrelated
		// message (one with no "seq" field at all) from crashing the parse below, and keeps the
		// "enough records have arrived" wait from being satisfied by someone else's messages instead
		// of this test's own 300.
		Set<String> myKeys = idsByOrder.keySet().stream().map(UUID::toString).collect(Collectors.toSet());
		int expectedTotal = ORDER_COUNT * ROWS_PER_ORDER;

		List<ConsumerRecord<String, String>> consumed = poll(Topics.ORDER_CREATED, Duration.ofSeconds(90),
				collected -> collected.stream().filter(r -> myKeys.contains(r.key())).count() >= expectedTotal);

		Map<String, List<Integer>> seqByKey = new LinkedHashMap<>();
		for (ConsumerRecord<String, String> record : consumed) {
			if (!myKeys.contains(record.key())) {
				continue;
			}
			int seq = MAPPER.readTree(record.value()).get("seq").asInt();
			seqByKey.computeIfAbsent(record.key(), k -> new ArrayList<>()).add(seq);
		}

		assertThat(seqByKey).as("every order's rows all arrived").hasSize(ORDER_COUNT);

		// containsExactly, not containsExactlyInAnyOrder: this is checking sequence, not merely
		// presence. A relay that published a later row before an earlier one for the same order
		// would fail here even though every message still arrived.
		assertThat(seqByKey.values()).allSatisfy(
				seqList -> assertThat(seqList).containsExactly(0, 1, 2));
	}

	@Test
	void parkedRecordHaltsItsOwnOrderButNotOthers() {
		UUID stuckOrder = UUID.randomUUID();
		OutboxRecord blocker = outboxRepository.save(
				new OutboxRecord(stuckOrder, "no.such.channel", "{}", null, null));
		OutboxRecord blockedFollower = outboxRepository.save(
				new OutboxRecord(stuckOrder, Topics.ORDER_CREATED, "{}", null, null));

		UUID healthyOrder = UUID.randomUUID();
		OutboxRecord healthy = outboxRepository.save(
				new OutboxRecord(healthyOrder, Topics.ORDER_CREATED, "{}", null, null));

		for (int i = 0; i < maxAttempts; i++) {
			outboxRelay.pollAndPublish();
		}

		assertThat(outboxRepository.findById(blocker.getId()).orElseThrow().getStatus())
				.as("the poisoned row parks after exhausting its attempts")
				.isEqualTo(OutboxStatus.PARKED);

		assertThat(outboxRepository.findById(blockedFollower.getId()).orElseThrow().getStatus())
				.as("the later row for the SAME order must never be sent out of turn (FR-030)")
				.isEqualTo(OutboxStatus.PENDING);

		assertThat(outboxRepository.findById(healthy.getId()).orElseThrow().getStatus())
				.as("an unrelated order is completely unaffected")
				.isEqualTo(OutboxStatus.PUBLISHED);
	}

	private void runConcurrentlyUntilDrained() throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(RELAY_THREADS);
		try {
			List<Future<?>> futures = new ArrayList<>();
			for (int i = 0; i < RELAY_THREADS; i++) {
				futures.add(pool.submit(() -> {
					while (pendingCount() > 0) {
						outboxRelay.pollAndPublish();
					}
				}));
			}
			for (Future<?> future : futures) {
				future.get(120, TimeUnit.SECONDS);
			}
		} finally {
			pool.shutdown();
		}
	}

	private long pendingCount() {
		Long count = jdbc.queryForObject("SELECT count(*) FROM outbox WHERE status = 'PENDING'", Long.class);
		return count == null ? 0 : count;
	}
}
