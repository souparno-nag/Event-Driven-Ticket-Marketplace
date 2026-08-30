package com.marketplace.inventory.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.marketplace.events.Topics;
import com.marketplace.inventory.InventoryKafkaIT;
import com.marketplace.inventory.SeatingPlanFixture;
import com.marketplace.inventory.service.ReservationOutcome;
import com.marketplace.inventory.service.ReservationService;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;

/**
 * SC-015: proves this service's OWN half of "one connected trace" — a decision made while a trace is
 * active captures that SAME trace onto its outbox row, and the relay carries it onward into the
 * outgoing {@code seats.reserved}/{@code seats.rejected} message's headers, exactly the way
 * order-service's own {@code OutboxTracingIT} proves the identical mechanism for {@code OrderCreated}.
 *
 * <p>SCOPE, stated honestly: this is the PRODUCER half of this service's own contribution to SC-015's
 * single connected trace — decide, capture, relay, publish. The other half — this service correctly
 * ADOPTING an order-service-originated trace on the way IN, via the header extraction
 * {@code OrderCreatedListener} now performs (T186) — needs a message to genuinely flow through the
 * real consumer, which needs {@code IdempotencyGuard}'s own body (T174) to exist first. That half is
 * therefore not yet provable end-to-end; this test proves the half that already can be, using the same
 * three-argument {@code decide(...)} every User Story 1 and User Story 2 test already calls directly
 * (no message identity, no guard involved).
 */
class OutboxTracingIT extends InventoryKafkaIT {

	@Autowired
	ReservationService reservationService;

	@Autowired
	Tracer tracer;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Test
	void continuesTheOriginalTraceIntoTheOutgoingMessage() {
		var show = SeatingPlanFixture.provisionShow(jdbcTemplate, "OutboxTracing", 1);
		String seat = show.seatLabels().get(0);
		UUID orderId = UUID.randomUUID();

		// Simulates the moment a real consumed message would be handled: a span active only while
		// the decision is made -- exactly as OrderCreatedListener now keeps one active around its own
		// call to decide(...) -- and gone again the instant this block ends, just as a real message's
		// span ends once that message has been handled.
		String traceId;
		Span span = tracer.nextSpan().name("test-consuming-message").start();
		try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
			traceId = span.context().traceId();
			ReservationOutcome outcome = reservationService.decide(orderId, show.showId(), List.of(seat));
			assertThat(outcome).isInstanceOf(ReservationOutcome.Reserved.class);
		} finally {
			span.end();
		}

		String storedTraceparent = jdbcTemplate.queryForObject(
				"SELECT traceparent FROM outbox WHERE aggregate_id = ?", String.class, orderId);
		assertThat(storedTraceparent)
				.as("the row must have captured a trace context while the span was active")
				.isNotNull()
				.contains(traceId);

		// The relay runs on its own real schedule -- not called directly -- for the identical reason
		// OutboxRelayPortIT (T162) already established: proving the real timer fires is stronger than
		// proving the method works when called by hand.
		List<ConsumerRecord<String, String>> received = poll(Topics.SEATS_RESERVED, Duration.ofSeconds(20),
				collected -> collected.stream().anyMatch(r -> r.key().equals(orderId.toString())));

		Header header = received.stream()
				.filter(r -> r.key().equals(orderId.toString()))
				.findFirst().orElseThrow()
				.headers().lastHeader("traceparent");
		assertThat(header).as("the outgoing message must carry a traceparent header").isNotNull();

		String headerValue = new String(header.value(), StandardCharsets.UTF_8);
		assertThat(headerValue)
				.as("the header must continue the ORIGINAL trace, not start a new one")
				.contains(traceId);
	}
}
