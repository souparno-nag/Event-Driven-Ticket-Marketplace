package com.marketplace.orders.api;

import java.util.UUID;

import com.marketplace.orders.domain.OrderStatus;

/**
 * The 202 body returned by {@code POST /api/orders}. {@code status} is always {@link
 * OrderStatus#PENDING} here — this response says the request was accepted for processing, not that
 * a booking succeeded (FR-002). Serializes with {@code status} as the plain enum name ("PENDING"),
 * which is Jackson's default for an enum with no custom {@code @JsonValue}.
 */
public record CreateOrderResponse(UUID orderId, OrderStatus status) {
}
