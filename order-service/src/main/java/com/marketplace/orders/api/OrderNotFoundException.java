package com.marketplace.orders.api;

import java.util.UUID;

/**
 * No order exists with the given identifier. Thrown by {@link OrderController#getOrder} once
 * {@code orderId} has already been successfully parsed as a {@link UUID} — a caller who sends
 * something that ISN'T a well-formed UUID never reaches this exception at all; that fails earlier,
 * in Spring's own path-variable conversion, which is exactly what keeps the two failures reportable
 * as genuinely distinct problems (FR-021, see {@link ApiExceptionHandler}).
 */
public class OrderNotFoundException extends RuntimeException {

	public OrderNotFoundException(UUID orderId) {
		super("No order exists with id " + orderId);
	}
}
