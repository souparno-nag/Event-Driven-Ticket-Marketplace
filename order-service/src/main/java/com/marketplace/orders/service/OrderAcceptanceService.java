package com.marketplace.orders.service;

import java.util.LinkedHashSet;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.marketplace.orders.api.CreateOrderRequest;
import com.marketplace.orders.domain.Order;
import com.marketplace.orders.domain.OrderRepository;
import com.marketplace.orders.outbox.OutboxRecord;
import com.marketplace.orders.outbox.OutboxRepository;
import com.marketplace.orders.outbox.OutboxWriter;

/**
 * Accepts a booking request: writes the order and the outbox row announcing it, together.
 *
 * <p>WHY {@link #acceptOrder} is deliberately the ONLY place either table is written from this
 * service: FR-007 requires the two writes to be genuinely atomic, and the only way to make that
 * claim reviewable — rather than merely hoped for — is to have exactly one method where both saves
 * happen, inside one {@code @Transactional} boundary. A reviewer checking FR-007 has exactly one
 * method to read.
 *
 * <p>No message is sent here, and none should ever be added here. Sending is the relay's job
 * (T097), running after this transaction has already committed — see {@code contracts/outbox-relay.md}.
 * Publishing inside this method would reopen the exact gap the outbox pattern exists to close: a
 * send that appears to succeed but whose surrounding transaction later rolls back.
 */
@Service
public class OrderAcceptanceService {

	private final OrderRepository orderRepository;
	private final OutboxRepository outboxRepository;
	private final OutboxWriter outboxWriter;

	public OrderAcceptanceService(
			OrderRepository orderRepository, OutboxRepository outboxRepository, OutboxWriter outboxWriter) {
		this.orderRepository = orderRepository;
		this.outboxRepository = outboxRepository;
		this.outboxWriter = outboxWriter;
	}

	/**
	 * Persists a new, {@code PENDING} order and its {@code order.created} outbox row in one
	 * transaction, and returns the order's identifier.
	 *
	 * <p>The identifier is generated here, before either row is written — see {@link Order}'s own
	 * javadoc for why the application assigns it rather than the database.
	 */
	@Transactional
	public UUID acceptOrder(CreateOrderRequest request) {
		UUID orderId = UUID.randomUUID();

		Order order = new Order(
				orderId, request.userId(), request.showId(),
				new LinkedHashSet<>(request.seatIds()), request.amount());
		orderRepository.save(order);

		OutboxRecord outboxRecord = outboxWriter.writeOrderCreated(order);
		outboxRepository.save(outboxRecord);

		return orderId;
	}
}
