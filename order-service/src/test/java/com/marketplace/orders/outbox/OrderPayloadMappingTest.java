package com.marketplace.orders.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.marketplace.events.OrderCreated;
import com.marketplace.orders.domain.Order;

/**
 * Specifies the pure mapping from {@link Order} to {@link OrderCreated}, before {@code OutboxWriter}
 * (T081) exists.
 *
 * <p>WHY this exercises a static method rather than the full {@code OutboxWriter} instance: the
 * class described in T081 also captures the active W3C trace context and serializes the result,
 * neither of which this test is about — that behaviour belongs to {@code OutboxTracingIT} (T090),
 * once the relay exists in Phase 4. Testing the mapping in isolation means this test needs no Spring
 * context and no tracer, and runs in milliseconds. The design intent, recorded here so T081 lands
 * consistent with it: {@code OutboxWriter} exposes a {@code static OrderCreated toOrderCreated(Order,
 * UUID messageId, Instant occurredAt)} as a pure function, separate from the instance method that
 * wraps it with tracing and serialization.
 *
 * <p>Will not compile until {@code OutboxWriter} exists. That is the intended state.
 */
class OrderPayloadMappingTest {

	@Test
	void theMappingCarriesEveryFieldAndSagaIdEqualsOrderId() {
		Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				Set.of("A1", "A2"), new BigDecimal("150.00"));
		UUID messageId = UUID.randomUUID();
		Instant occurredAt = Instant.now();

		OrderCreated event = OutboxWriter.toOrderCreated(order, messageId, occurredAt);

		assertThat(event.messageId()).isEqualTo(messageId);

		// FR-003 / step 1's correlation rule, restated at the point it is produced: the saga
		// identifier IS the order identifier, never a separately generated value.
		assertThat(event.sagaId()).isEqualTo(order.getId());
		assertThat(event.orderId()).isEqualTo(order.getId());
		assertThat(event.sagaId()).isEqualTo(event.orderId());

		assertThat(event.userId()).isEqualTo(order.getUserId());

		// showId, never a message identity -- the exact confusion step 1's rename exists to prevent.
		assertThat(event.showId()).isEqualTo(order.getShowId());

		assertThat(event.seatIds()).containsExactlyInAnyOrderElementsOf(order.getSeatIds());
		assertThat(event.amount()).isEqualByComparingTo(order.getAmount());
		assertThat(event.occurredAt()).isEqualTo(occurredAt);
		assertThat(event.schemaVersion()).isEqualTo(1);
	}

	@Test
	void theSerializedAmountIsAPlainDecimalNeverScientificNotation() throws Exception {
		Order order = new Order(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
				Set.of("A1"), new BigDecimal("150.00"));
		OrderCreated event = OutboxWriter.toOrderCreated(order, UUID.randomUUID(), Instant.now());

		// Mirrors JacksonConfig's settings rather than depending on Spring, so this stays a fast unit
		// test: the trap this asserts against — WRITE_BIGDECIMAL_AS_PLAIN unset — is a JacksonConfig
		// (T070) concern, and this test exists to prove the SERIALIZED FORM, not to exercise the bean.
		ObjectMapper mapper = JsonMapper.builder()
				.addModule(new JavaTimeModule())
				.enable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN)
				.build();

		String json = mapper.writeValueAsString(event);

		assertThat(json).contains("150.00");
		assertThat(json.toUpperCase()).doesNotContain("E+2").doesNotContain("1.5E2");
	}
}
