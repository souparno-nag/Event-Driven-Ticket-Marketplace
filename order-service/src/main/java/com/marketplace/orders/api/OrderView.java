package com.marketplace.orders.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.marketplace.orders.domain.Order;
import com.marketplace.orders.domain.OrderStatus;

/**
 * The body returned by {@code GET /api/orders/{orderId}}. See {@code contracts/orders-api.yaml} for
 * the wire shape this must match.
 *
 * <p>{@code amount} is typed {@link String} here, not {@link java.math.BigDecimal} — the contract
 * says the wire format is a JSON string (`"150.00"`, not `150.00`), the same shape
 * {@code CreateOrderRequest} accepts on the way in. Unlike that record, this one only ever
 * serializes, never deserializes, so there is no flexible-input trick to lean on: the field has to
 * already be the string the contract promises, which is why {@link #from} converts it explicitly
 * with {@code toPlainString()} rather than handing a {@code BigDecimal} to Jackson and hoping.
 */
public record OrderView(
		UUID orderId,
		UUID userId,
		UUID showId,
		List<String> seatIds,
		String amount,
		OrderStatus status,
		Instant createdAt,
		Instant updatedAt) {

	/**
	 * Seats are sorted here, not left in whatever order {@link Order#getSeatIds()} happens to return
	 * them — that set has no defined iteration order of its own, so an unsorted response would be a
	 * different, arbitrary order on every read of the very same order. Sorting once, here, is what
	 * makes the response deterministic (T103).
	 */
	public static OrderView from(Order order) {
		return new OrderView(
				order.getId(),
				order.getUserId(),
				order.getShowId(),
				order.getSeatIds().stream().sorted().toList(),
				order.getAmount().toPlainString(),
				order.getStatus(),
				order.getCreatedAt(),
				order.getUpdatedAt());
	}
}
