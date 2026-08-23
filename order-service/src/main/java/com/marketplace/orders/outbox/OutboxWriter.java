package com.marketplace.orders.outbox;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketplace.events.OrderCreated;
import com.marketplace.events.Topics;
import com.marketplace.orders.domain.Order;

import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

/**
 * Builds the outbox row that announces a newly accepted order.
 *
 * <p>Two responsibilities live here, deliberately kept apart:
 *
 * <ul>
 *   <li>{@link #toOrderCreated} — the pure mapping from {@link Order} to {@link OrderCreated}.
 *       Static, no dependencies, testable in isolation (see {@code OrderPayloadMappingTest}, T074).
 *   <li>{@link #writeOrderCreated} — the full job: map, serialize, capture the active trace context,
 *       and wrap the result in an unsaved {@link OutboxRecord}. This is the method
 *       {@code OrderAcceptanceService} (T082) actually calls.
 * </ul>
 *
 * <p>WHY the trace context is captured here rather than left to the relay: the accepting request's
 * trace is only active <em>now</em>, while this row is being built. By the time the relay sends this
 * row — 500ms to several seconds later, per {@code outbox.relay.poll-interval-ms} — the request that
 * caused it is long finished and its trace context is gone from this thread. Capturing it here, onto
 * the row itself, is what lets the relay continue the same trace when it eventually publishes.
 *
 * <p>WHY {@code traceparent}/{@code tracestate} are fields on {@link OutboxRecord} rather than fields
 * on {@link OrderCreated}: {@code OrderCreated} is a frozen contract shared by every service, and
 * observability concerns must never force a version bump on it (FR-024, from build step 1). Keeping
 * trace context on the outbox row — which no consumer ever sees — is what keeps that promise.
 */
@Component
public class OutboxWriter {

	private final ObjectMapper objectMapper;
	private final Tracer tracer;
	private final Propagator propagator;

	public OutboxWriter(ObjectMapper objectMapper, Tracer tracer, Propagator propagator) {
		this.objectMapper = objectMapper;
		this.tracer = tracer;
		this.propagator = propagator;
	}

	/**
	 * Maps an {@link Order} to the {@link OrderCreated} message that announces it. Pure: no I/O, no
	 * tracing, no serialization — see {@code OrderPayloadMappingTest} (T074) for what this must
	 * guarantee about the result.
	 *
	 * @param order      the order being announced
	 * @param messageId  identity of this specific message — the consumer's idempotency key
	 * @param occurredAt when the order was accepted
	 */
	public static OrderCreated toOrderCreated(Order order, UUID messageId, Instant occurredAt) {
		return new OrderCreated(
				messageId,
				order.getId(),
				occurredAt,
				1,
				order.getId(),
				order.getUserId(),
				order.getShowId(),
				List.copyOf(order.getSeatIds()),
				order.getAmount());
	}

	/**
	 * Builds the complete, unsaved outbox row for a newly accepted order: the mapped message,
	 * serialized with the service's configured {@link ObjectMapper} (so a money amount is written
	 * plain, never in scientific notation — T070), and the active trace context, if any.
	 *
	 * <p>The channel is always {@link Topics#ORDER_CREATED} — never a literal string — so a typo in
	 * a channel name is a compile error rather than a message silently written where nothing
	 * subscribes (FR-025, R4).
	 */
	public OutboxRecord writeOrderCreated(Order order) {
		OrderCreated event = toOrderCreated(order, UUID.randomUUID(), Instant.now());
		String payload = serialize(event, order);

		// WHY the context is checked for null rather than injected unconditionally: this method is
		// called from a plain @Transactional service method with no HTTP request wrapping it during
		// this build step's own tests (OrderAcceptanceIT calls the service directly), so there is
		// often no active span to propagate. FR-027 requires that case to still produce a valid,
		// untraced row rather than an error.
		Map<String, String> carrier = new HashMap<>();
		TraceContext context = tracer.currentTraceContext().context();
		if (context != null) {
			propagator.inject(context, carrier, Map::put);
		}

		return new OutboxRecord(
				order.getId(),
				Topics.ORDER_CREATED,
				payload,
				carrier.get("traceparent"),
				carrier.get("tracestate"));
	}

	private String serialize(OrderCreated event, Order order) {
		try {
			return objectMapper.writeValueAsString(event);
		} catch (JsonProcessingException e) {
			// A message built from an already-validated, already-persisted Order should always be
			// serializable. Reaching this catch means a message field itself is broken in a way no
			// earlier check caught — a defect worth failing loudly on, not swallowing.
			throw new IllegalStateException("Failed to serialize OrderCreated for order " + order.getId(), e);
		}
	}
}
