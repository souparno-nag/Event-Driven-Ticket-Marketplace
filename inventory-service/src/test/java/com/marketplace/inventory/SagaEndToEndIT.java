package com.marketplace.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.marketplace.events.OrderCreated;
import com.marketplace.events.SeatsReserved;

/**
 * SC-009: a real {@code OrderCreated}, published the same way order-service actually publishes one,
 * produces a real {@code seats.reserved} an INDEPENDENT consumer can read back — keyed by the saga id
 * and deserializing into the value this service genuinely decided (FR-042).
 *
 * <p>This is the producer/consumer contract test the project constitution requires, and the first one
 * in the whole project with both halves actually present: order-service has published
 * {@code OrderCreated} since step 2, but nothing has ever consumed it until this service does. Every
 * earlier test proving this service's own decision logic ({@code ReservationContentionIT},
 * {@code ReservationRejectionIT}, and the rest) called {@code ReservationService.decide(...)}
 * directly, deliberately bypassing Kafka (research.md R10) — which is the right choice for proving
 * concurrency and decision logic, but proves nothing about whether a message actually arriving on the
 * real channel, in the real wire shape, is what triggers that logic at all. This test is the one place
 * that question gets asked.
 *
 * <p>WHY {@code publishOrderCreated} rather than constructing the record and calling a listener method
 * directly: the point is exactly this class's own name says — an END TO END saga hop, not a decision.
 * Calling a listener method directly would prove the decision logic again, which every other test
 * already does, and would prove nothing about the {@code @KafkaListener} wiring, the deserializer, or
 * whether the message this service reads is genuinely the message order-service would have sent.
 *
 * <p>Expected to fail until User Story 3 exists: nothing in this service consumes {@code order.created}
 * yet ({@code OrderCreatedListener} is T178), so {@code awaitSeatsReserved} times out. That is the
 * correct state for this checkpoint, not a bug in this test — the identical situation T163 already
 * recorded for quickstart's own S1 and S4.
 */
class SagaEndToEndIT extends InventoryKafkaIT {

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Test
	void producesOneOutcomeKeyedBySagaIdAndDeserializableByAnIndependentReader() {
		var show = SeatingPlanFixture.provisionShow(jdbcTemplate, "SagaEndToEnd", 1);
		String seat = show.seatLabels().get(0);

		UUID orderId = UUID.randomUUID();
		OrderCreated event = new OrderCreated(
				UUID.randomUUID(), orderId, Instant.now(), 1,
				orderId, UUID.randomUUID(), show.showId(), List.of(seat), new BigDecimal("42.00"));

		publishOrderCreated(event);

		// awaitSeatsReserved reads the wire with WIRE_MAPPER -- an ObjectMapper built independently
		// of this service's own JacksonConfig (see InventoryKafkaIT's own Javadoc) -- which is what
		// makes a successful deserialization here evidence the CONTRACT is readable, not merely that
		// this service agrees with itself.
		SeatsReserved reserved = awaitSeatsReserved(orderId, Duration.ofSeconds(15));

		assertThat(reserved.sagaId()).isEqualTo(orderId);
		assertThat(reserved.seatIds()).containsExactly(seat);
		assertThat(reserved.lockExpiresAt()).isAfter(reserved.occurredAt());

		// The durable record this service itself produced must agree with what the independent
		// reader saw on the wire -- SC-009 is a claim about the whole path, not just the last hop.
		String status = jdbcTemplate.queryForObject(
				"SELECT status FROM reservations WHERE order_id = ?", String.class, orderId);
		assertThat(status).isEqualTo("HELD");
	}
}
