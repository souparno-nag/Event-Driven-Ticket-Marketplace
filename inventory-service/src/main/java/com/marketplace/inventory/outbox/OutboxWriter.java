package com.marketplace.inventory.outbox;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketplace.events.SagaEvent;
import com.marketplace.events.SeatsRejected;
import com.marketplace.events.SeatsReserved;
import com.marketplace.events.Topics;
import com.marketplace.inventory.service.ReservationOutcome;

import io.micrometer.tracing.TraceContext;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

/**
 * Builds the outbox row that announces a decided booking outcome.
 *
 * <p>Two responsibilities, deliberately kept apart — the same split order-service's own
 * {@code OutboxWriter} makes for {@code OrderCreated}:
 *
 * <ul>
 *   <li>{@link #toMessage} — the pure mapping from a decided {@link ReservationOutcome} to the
 *       message it produces. Static, no dependencies, testable in isolation ({@code OutcomeMappingTest},
 *       T150).
 *   <li>{@link #write} — the full job: map, serialize, capture the active trace context, and wrap the
 *       result in an unsaved {@link OutboxRecord}. This is the method {@code ReservationService}
 *       (T160) actually calls.
 * </ul>
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
	 * Maps a decided outcome to the exact message it produces. Pure: no I/O, no serialization, no
	 * tracing.
	 *
	 * <p>{@code seatIds} is sorted before either message is built — the frozen contracts make no
	 * promise about request order, and a stable, sorted wire representation is what lets two readers
	 * of the same message agree on what it says without depending on incidental ordering upstream.
	 *
	 * <p>{@code occurredAt} and, for a {@link ReservationOutcome.Reserved}, {@code lockExpiresAt} are
	 * NOT derived from two separate {@code Instant.now()} calls here — {@code occurredAt} is the one
	 * instant this method is handed, and {@code lockExpiresAt} is read straight off the outcome,
	 * which already carries the exact value stored on the reservation's own row (FR-009). Building
	 * both from a single already-decided instant is what the frozen contract's strict-inequality
	 * requirement depends on.
	 */
	public static SagaEvent toMessage(UUID orderId, List<String> seatIds, ReservationOutcome outcome, Instant occurredAt) {
		List<String> sorted = seatIds.stream().sorted().toList();
		return switch (outcome) {
			case ReservationOutcome.Reserved reserved -> new SeatsReserved(
					UUID.randomUUID(), orderId, occurredAt, 1, orderId,
					sorted, reserved.reservationId(), reserved.lockExpiresAt());
			case ReservationOutcome.Rejected rejected -> new SeatsRejected(
					UUID.randomUUID(), orderId, occurredAt, 1, orderId, sorted, rejected.reason());
		};
	}

	/**
	 * Builds the complete, unsaved outbox row for a decided outcome: the mapped message, serialized
	 * with this service's configured {@link ObjectMapper} (so a value is written exactly as this
	 * service decided it, never re-derived at send time — {@code JacksonConfig}), and the active
	 * trace context, if any.
	 *
	 * <p>{@code event_type} is {@link Topics#SEATS_RESERVED} or {@link Topics#SEATS_REJECTED} —
	 * decided by which case {@code outcome} actually is, never a literal string typed here, so a
	 * mismatch between the message built and the channel it is announced under is a compile error
	 * rather than a message written where the wrong consumers listen.
	 */
	public OutboxRecord write(UUID orderId, List<String> seatIds, ReservationOutcome outcome, Instant occurredAt) {
		SagaEvent message = toMessage(orderId, seatIds, outcome, occurredAt);
		String eventType = switch (outcome) {
			case ReservationOutcome.Reserved ignored -> Topics.SEATS_RESERVED;
			case ReservationOutcome.Rejected ignored -> Topics.SEATS_REJECTED;
		};
		String payload = serialize(message, orderId);

		// WHY the context is checked for null rather than injected unconditionally: this method runs
		// from a plain @Transactional service method, not every call site is guaranteed to have an
		// active span (a direct-call concurrency test, most obviously — ReservationContentionIT calls
		// ReservationService with no HTTP request or consumed message wrapping it). FR-047's own
		// insistence on never fabricating a fact applies here too: a row with nothing to capture must
		// still be a valid, untraced row, not an error.
		Map<String, String> carrier = new HashMap<>();
		TraceContext context = tracer.currentTraceContext().context();
		if (context != null) {
			propagator.inject(context, carrier, Map::put);
		}

		return new OutboxRecord(orderId, eventType, payload, carrier.get("traceparent"), carrier.get("tracestate"));
	}

	private String serialize(SagaEvent message, UUID orderId) {
		try {
			return objectMapper.writeValueAsString(message);
		} catch (JsonProcessingException e) {
			// A message built from an already-decided outcome should always be serializable.
			// Reaching this catch means a message field itself is broken in a way nothing earlier
			// caught -- a defect worth failing loudly on, not swallowing.
			throw new IllegalStateException("Failed to serialize outcome message for order " + orderId, e);
		}
	}
}
