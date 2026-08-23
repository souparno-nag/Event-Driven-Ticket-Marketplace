package com.marketplace.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import com.marketplace.events.Topics;
import com.marketplace.orders.api.CreateOrderRequest;
import com.marketplace.orders.domain.OrderRepository;
import com.marketplace.orders.outbox.OutboxRecord;
import com.marketplace.orders.outbox.OutboxRepository;
import com.marketplace.orders.service.OrderAcceptanceService;

/**
 * Specifies the atomicity guarantee at the heart of this build step: the order row and its outbox
 * row are written in one transaction, or neither is (FR-007).
 *
 * <p>Extends {@link PostgresIT} rather than a Kafka-backed base — this story is about the database
 * write, not the send, and should not pay for a broker it never touches.
 *
 * <p>Will not compile until {@code CreateOrderRequest} (T079) and {@code OrderAcceptanceService}
 * (T082) exist. That is the intended state.
 */
class OrderAcceptanceIT extends PostgresIT {

	@Autowired
	private OrderAcceptanceService orderAcceptanceService;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private OutboxRepository outboxRepository;

	@Test
	void oneAcceptedRequestWritesExactlyOneOrderAndOneOutboxRowSharingTheSameId() {
		UUID orderId = orderAcceptanceService.acceptOrder(validRequest());

		assertThat(orderRepository.findById(orderId)).isPresent();

		List<OutboxRecord> matching = outboxRepository.findAll().stream()
				.filter(row -> row.getAggregateId().equals(orderId))
				.toList();

		assertThat(matching).hasSize(1);
		assertThat(matching.get(0).getEventType()).isEqualTo(Topics.ORDER_CREATED);
	}

	/**
	 * SC-001: at least 200 concurrent submissions, verified by counting orders and outbox rows this
	 * test itself created — not by asserting on the whole table, which the shared-container discipline
	 * documented in {@link PostgresIT} rules out.
	 */
	@Test
	void twoHundredConcurrentAcceptancesProduceTwoHundredOrdersAndOutboxRows() throws Exception {
		int count = 200;
		ExecutorService pool = Executors.newFixedThreadPool(32);
		try {
			List<Future<UUID>> futures = new ArrayList<>();
			for (int i = 0; i < count; i++) {
				futures.add(pool.submit(() -> orderAcceptanceService.acceptOrder(validRequest())));
			}

			List<UUID> orderIds = new ArrayList<>();
			for (Future<UUID> future : futures) {
				orderIds.add(future.get(30, TimeUnit.SECONDS));
			}

			assertThat(orderIds).hasSize(count);
			// Every accepted order has its own distinct identifier -- no interleaving produced a
			// shared one.
			assertThat(new HashSet<>(orderIds)).hasSize(count);

			assertThat(orderRepository.findAllById(orderIds)).hasSize(count);

			long outboxMatches = outboxRepository.findAll().stream()
					.filter(row -> orderIds.contains(row.getAggregateId()))
					.count();
			assertThat(outboxMatches).isEqualTo(count);
		} finally {
			pool.shutdown();
		}
	}

	/**
	 * SC-002: a forced failure writing the outbox row must roll the order back too.
	 *
	 * <p>{@code @MockBean} inside a {@code @Nested} class gives this one test its own application
	 * context with {@link OutboxRepository} replaced by a mock, while every other test in this file
	 * keeps using the real one — a stubbed failure here must not contaminate the concurrency test
	 * above.
	 */
	@Nested
	class RollbackWhenTheOutboxWriteFails {

		@MockBean
		private OutboxRepository outboxRepository;

		@Autowired
		private OrderAcceptanceService orderAcceptanceService;

		@Autowired
		private OrderRepository orderRepository;

		@Test
		void aFailedOutboxWriteRollsBackTheOrderToo() {
			when(outboxRepository.save(any())).thenThrow(new RuntimeException("simulated outbox failure"));

			assertThatThrownBy(() -> orderAcceptanceService.acceptOrder(validRequest()))
					.isInstanceOf(RuntimeException.class);

			ArgumentCaptor<OutboxRecord> captor = ArgumentCaptor.forClass(OutboxRecord.class);
			verify(outboxRepository).save(captor.capture());

			UUID attemptedOrderId = captor.getValue().getAggregateId();
			assertThat(orderRepository.findById(attemptedOrderId)).isEmpty();
		}
	}

	private static CreateOrderRequest validRequest() {
		String seat = "A" + UUID.randomUUID().toString().substring(0, 8);
		return new CreateOrderRequest(UUID.randomUUID(), UUID.randomUUID(), List.of(seat), new BigDecimal("10.00"));
	}
}
