package com.marketplace.orders.api;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.marketplace.orders.domain.Order;
import com.marketplace.orders.domain.OrderRepository;
import com.marketplace.orders.domain.OrderStatus;
import com.marketplace.orders.service.OrderAcceptanceService;

import jakarta.validation.Valid;

/**
 * {@code POST /api/orders} and {@code GET /api/orders/{orderId}} — the front door of the
 * marketplace. See {@code contracts/orders-api.yaml} for the full HTTP contract this implements.
 */
@RestController
@RequestMapping("/api/orders")
public class OrderController {

	private final OrderAcceptanceService orderAcceptanceService;
	private final OrderRepository orderRepository;

	public OrderController(OrderAcceptanceService orderAcceptanceService, OrderRepository orderRepository) {
		this.orderAcceptanceService = orderAcceptanceService;
		this.orderRepository = orderRepository;
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

	/**
	 * {@code orderId} is typed {@link UUID} directly on the method signature rather than accepted as
	 * a {@code String} and parsed by hand — Spring converts the path variable before this method
	 * ever runs, and a value that fails that conversion never reaches this body at all. That is what
	 * lets a malformed identifier and an unknown-but-well-formed one be reported as genuinely
	 * different problems (FR-021): the first fails in conversion, before {@link OrderNotFoundException}
	 * — thrown only here, only after the id parsed successfully — ever gets a chance to be thrown.
	 */
	@GetMapping("/{orderId}")
	public OrderView getOrder(@PathVariable UUID orderId) {
		Order order = orderRepository.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
		return OrderView.from(order);
	}
}
