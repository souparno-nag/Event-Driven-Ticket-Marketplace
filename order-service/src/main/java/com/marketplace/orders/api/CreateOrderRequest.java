package com.marketplace.orders.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.marketplace.orders.api.validation.TwoDecimalPlaces;
import com.marketplace.orders.api.validation.UniqueElements;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * A booking request, as received at {@code POST /api/orders}. See
 * {@code contracts/orders-api.yaml} for the wire shape this must match.
 *
 * <p>{@code amount} is typed {@link BigDecimal} but the wire format is a JSON <em>string</em>
 * (`"10.00"`, not `10.00`) — Jackson's default {@code BigDecimal} deserializer accepts either form,
 * so no custom deserializer is needed here; the string form on the wire is what stops a client-side
 * parser from ever turning the amount into a binary float before it reaches this service at all.
 *
 * <p>Every constraint here is testable in complete isolation from how it is implemented — see
 * {@code CreateOrderRequestValidationTest} (T073), which asks a plain {@code Validator} what is
 * wrong with an instance and knows nothing about {@code @UniqueElements} or {@code @TwoDecimalPlaces}
 * existing at all.
 */
public record CreateOrderRequest(

		@NotNull
		UUID userId,

		@NotNull
		UUID showId,

		@NotEmpty
		@UniqueElements
		List<@Size(max = 32) String> seatIds,

		@NotNull
		@DecimalMin(value = "0.00")
		@TwoDecimalPlaces
		BigDecimal amount) {
}
