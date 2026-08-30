package com.marketplace.inventory.consume;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.marketplace.events.OrderCreated;
import com.marketplace.events.Topics;
import com.marketplace.inventory.service.ReservationService;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;

/**
 * The one place a real {@code order.created} message becomes a call into
 * {@link ReservationService} — the whole reason every other class in this build step exists
 * (contracts/inventory-consumer.md).
 *
 * <p>WHY this class carries no {@code @Transactional} of its own, even though the work it triggers is
 * entirely transactional: the transaction belongs to the DECISION, not to the act of receiving a
 * message. {@code ReservationService.decide(...)} already opens (and, on an optimistic-lock collision,
 * reopens) its own transaction via {@code TransactionTemplate} — annotating this listener as well
 * would nest a second transactional boundary around the container's own error-handling bookkeeping
 * (offset tracking, the {@code DefaultErrorHandler}'s retry accounting), which
 * contracts/inventory-consumer.md's own implementation notes call out as specifically the wrong thing
 * to enclose.
 *
 * <p>WHY throwing is the entire mechanism, with no manual acknowledgement anywhere: Spring Kafka
 * commits a listener's offset only after the method returns normally. A schema check that fails, or
 * any exception {@code ReservationService.decide(...)} lets escape, leaves the offset uncommitted —
 * which is what makes {@code KafkaConsumerConfig}'s {@code DefaultErrorHandler} redeliver the message
 * at all. Committing the offset by hand anywhere in this class would create a second place the offset
 * could advance past work that never actually happened.
 *
 * <p>WHY this class extracts the incoming trace context BY HAND — found necessary, not assumed, while
 * verifying SC-015 (T186): {@link KafkaConsumerConfig}'s consumer factory and container factory are
 * both built directly with {@code new}, the same way order-service's own producer factory is —
 * neither goes through Spring Boot's own auto-configured Kafka beans, which is the ONLY path that
 * wires up automatic, Observation-based trace propagation without being asked. Order-service's own
 * outbox already captures and forwards a trace this same manual way (see {@code OutboxWriter} and
 * {@code OutboxRelay#attachTraceHeaders} in both services) — this listener is simply the other end of
 * that same pipe, and the first CONSUMER anywhere in this project, so nothing existed yet to adopt an
 * incoming trace before this class did.
 */
@Component
public class OrderCreatedListener {

	/**
	 * The only {@code schemaVersion} this listener currently knows how to interpret.
	 * {@link com.marketplace.events.OrderCreated}'s own compact constructor only enforces "at least
	 * 1" — a bound every message shape shares — never "a version THIS consumer understands", because
	 * that knowledge belongs to whichever consumer is doing the interpreting (FR-003).
	 */
	private static final int SUPPORTED_SCHEMA_VERSION = 1;

	private final ReservationService reservationService;
	private final Tracer tracer;
	private final Propagator propagator;

	public OrderCreatedListener(ReservationService reservationService, Tracer tracer, Propagator propagator) {
		this.reservationService = reservationService;
		this.tracer = tracer;
		this.propagator = propagator;
	}

	/**
	 * @param record the raw Kafka record, not merely its already-deserialized value — needed here
	 *               specifically to reach the {@code traceparent}/{@code tracestate} headers
	 *               {@code OutboxRelay#attachTraceHeaders} attached when this message was published.
	 *               A message this listener could not even parse never reaches this method at all;
	 *               it is routed to the dead-letter channel by the container's own error handler
	 *               before a {@code ConsumerRecord} is ever handed to a {@code @KafkaListener} method
	 * @throws UnknownSchemaVersionException if {@code event.schemaVersion()} is not
	 *                                        {@value #SUPPORTED_SCHEMA_VERSION} — classified
	 *                                        non-retryable by {@code KafkaConsumerConfig}, so this
	 *                                        throw reaches the dead-letter channel immediately rather
	 *                                        than after exhausting a redelivery schedule that could
	 *                                        never have produced a different answer (FR-003)
	 */
	@KafkaListener(topics = Topics.ORDER_CREATED, containerFactory = "kafkaListenerContainerFactory")
	public void onMessage(ConsumerRecord<String, OrderCreated> record) {
		OrderCreated event = record.value();
		if (event.schemaVersion() != SUPPORTED_SCHEMA_VERSION) {
			throw new UnknownSchemaVersionException(event.schemaVersion());
		}

		// Reconstructs the trace order-service's own outbox started, exactly the way OutboxRelay's
		// own attachTraceHeaders does for the OUTGOING half of this exact handoff. A message with no
		// stored headers (nothing was tracing when order-service wrote its own row) extracts to a
		// context-less span, started fresh -- never an error, matching this whole project's rule that
		// a missing trace context is a valid, untraced state, not a defect (FR-047).
		Map<String, String> carrier = new HashMap<>();
		Header traceparent = record.headers().lastHeader("traceparent");
		if (traceparent != null) {
			carrier.put("traceparent", new String(traceparent.value(), StandardCharsets.UTF_8));
		}
		Header tracestate = record.headers().lastHeader("tracestate");
		if (tracestate != null) {
			carrier.put("tracestate", new String(tracestate.value(), StandardCharsets.UTF_8));
		}

		Span span = propagator.extract(carrier, Map::get).name("order.created.consume").start();
		try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
			// The return value is deliberately ignored here: a caller triggered by a real Kafka
			// message has nothing further to do with the decision either way. A NEW decision was
			// already recorded and its outbox row already written, inside decide(...)'s own
			// transaction, WHILE this span was active -- which is what lets OutboxWriter's own
			// "capture the active trace context" logic capture THIS continued trace rather than
			// nothing. An empty Optional means this exact message was already handled by an earlier
			// delivery, and doing nothing further is precisely the correct response to that (FR-030).
			reservationService.decide(event.messageId(), event.orderId(), event.showId(), event.seatIds());
		} finally {
			span.end();
		}
	}
}
