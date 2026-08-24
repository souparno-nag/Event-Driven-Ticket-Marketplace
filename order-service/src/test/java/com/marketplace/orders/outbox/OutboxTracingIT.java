package com.marketplace.orders.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.marketplace.events.Topics;
import com.marketplace.orders.KafkaPostgresIT;
import com.marketplace.orders.domain.Order;
import com.marketplace.orders.domain.OrderRepository;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

/**
 * Specifies guarantees 9–10 of {@code contracts/outbox-relay.md}: a stored trace context is injected
 * into the outgoing message's headers, and a row with none is still sent, untraced, without error.
 *
 * <p>Will not compile until {@code OutboxRelay} exists (T097). This is the direct continuation of
 * the trace-continuity story {@link OutboxWriter} started in T081: that class captures the context
 * <em>onto the row</em> at write time; this relay is what must put it back <em>into the outgoing
 * message</em> at send time. Between the two, {@code OutboxRecord} carries it across the gap.
 */
class OutboxTracingIT extends KafkaPostgresIT {

	@Autowired
	private OutboxRelay outboxRelay;

	@Autowired
	private OutboxRepository outboxRepository;

	@Autowired
	private OrderRepository orderRepository;

	@Autowired
	private OutboxWriter outboxWriter;

	@Autowired
	private Tracer tracer;

	@Test
	void continuesTheOriginalTrace() throws Exception {
		Order order = new Order(
				UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), Set.of("A1"), new BigDecimal("10.00"));
		orderRepository.save(order);

		// Simulates the accepting request: a span is active only while OutboxWriter builds the row,
		// exactly as it would be for one HTTP request handled by OrderController -- and gone again the
		// instant this block ends, just as a real request's span is gone once that request returns.
		String traceId;
		OutboxRecord record;
		Span span = tracer.nextSpan().name("test-accepting-request").start();
		try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
			traceId = span.context().traceId();
			record = outboxWriter.writeOrderCreated(order);
		} finally {
			span.end();
		}
		outboxRepository.save(record);
		assertThat(record.getTraceparent())
				.as("the row must have captured a trace context while the span was active")
				.isNotNull();

		// The relay runs well after the span above has ended -- exactly the gap the outbox pattern
		// creates between accepting a request and eventually sending its message.
		outboxRelay.pollAndPublish();

		ConsumerRecord<String, String> received = consume(Topics.ORDER_CREATED, 1, Duration.ofSeconds(10))
				.stream().filter(r -> r.key().equals(order.getId().toString())).findFirst().orElseThrow();

		Header header = received.headers().lastHeader("traceparent");
		assertThat(header).as("the outgoing message must carry a traceparent header").isNotNull();

		String headerValue = new String(header.value(), StandardCharsets.UTF_8);
		assertThat(headerValue)
				.as("the header must continue the ORIGINAL trace, not start a new one")
				.contains(traceId);
	}

	@Test
	void sendsUntracedRecord() {
		// No active span anywhere in this test -- built directly against the repository rather than
		// through OutboxWriter, so there is no trace context to capture at all (FR-027's exact case).
		UUID aggregateId = UUID.randomUUID();
		OutboxRecord record = new OutboxRecord(aggregateId, Topics.ORDER_CREATED, "{}", null, null);
		outboxRepository.save(record);

		outboxRelay.pollAndPublish();

		ConsumerRecord<String, String> received = consume(Topics.ORDER_CREATED, 1, Duration.ofSeconds(10))
				.stream().filter(r -> r.key().equals(aggregateId.toString())).findFirst().orElseThrow();

		assertThat(received.headers().lastHeader("traceparent"))
				.as("no header was ever stored, so none should be invented on the way out")
				.isNull();

		List<OutboxRecord> reloaded = outboxRepository.findAllById(List.of(record.getId()));
		assertThat(reloaded.get(0).getStatus())
				.as("a missing trace context must not stop the send from succeeding")
				.isEqualTo(OutboxStatus.PUBLISHED);
	}
}
