package com.marketplace.orders.api;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.marketplace.orders.domain.OrderStatus;
import com.marketplace.orders.service.OrderAcceptanceService;

import jakarta.validation.Valid;

/**
 * {@code POST /api/orders} — the front door of the marketplace. See {@code contracts/orders-api.yaml}
 * for the full HTTP contract this implements.
 *
 * <p>The GET endpoint for reading an order back (User Story 3) is added in T104, not here — this
 * task is scoped to acceptance alone.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

	private final OrderAcceptanceService orderAcceptanceService;

	public OrderController(OrderAcceptanceService orderAcceptanceService) {
		this.orderAcceptanceService = orderAcceptanceService;
	}

	/**
	 * TRADEOFF: responds {@code 202 Accepted}, not {@code 201 Created}. The order row exists the
	 * moment this returns, which is what {@code 201 Created} would ordinarily mean — but the
	 * <em>booking</em> does not: no seats are held and no payment has moved. {@code 201} here would
	 * tell a buyer they have seats when the saga has not even started (FR-002). {@code 202} says,
	 * correctly, "accepted for processing" and nothing more.
	 */
	@PostMapping
	public ResponseEntity<CreateOrderResponse> createOrder(@Valid @RequestBody CreateOrderRequest request) {
		UUID orderId = orderAcceptanceService.acceptOrder(request);

		return ResponseEntity
				.accepted()
				.location(URI.create("/api/orders/" + orderId))
				.body(new CreateOrderResponse(orderId, OrderStatus.PENDING));
	}
}
