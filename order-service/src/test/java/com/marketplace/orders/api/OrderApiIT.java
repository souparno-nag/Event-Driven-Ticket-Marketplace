package com.marketplace.orders.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import com.marketplace.orders.RelaySuppressedIT;
import com.marketplace.orders.domain.OrderRepository;

/**
 * Specifies the HTTP contract in {@code contracts/orders-api.yaml}: 202 with a Location header and
 * a PENDING status on acceptance, 400 naming the field on a malformed request, with nothing recorded
 * either way.
 *
 * <p>Deliberately written against raw JSON strings rather than the {@code CreateOrderRequest} Java
 * type — this is a test of the wire contract, and importing that class would couple this file to an
 * implementation detail it should not need to know about. Because of that, this file compiles
 * today, before {@code OrderController} (T083) exists; it will FAIL — every assertion expecting 202
 * will see 404, since nothing yet maps {@code /api/orders} — rather than fail to compile. That is a
 * more faithful "red" than a compile error: it proves the test is asking the right question of a
 * system that does not yet answer it, rather than merely failing to parse.
 */
@AutoConfigureMockMvc
class OrderApiIT extends RelaySuppressedIT {

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	private OrderRepository orderRepository;

	@Test
	void acceptedRequestReturns202WithLocationAndPendingStatus() throws Exception {
		mockMvc.perform(post("/api/orders")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
								"[\"A1\"]", "\"10.00\"")))
				.andExpect(status().isAccepted())
				.andExpect(header().exists("Location"))
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.orderId").exists());
	}

	@Test
	void emptySeatListIsRejectedAndNothingIsRecorded() throws Exception {
		long before = orderRepository.count();

		mockMvc.perform(post("/api/orders")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(UUID.randomUUID().toString(), UUID.randomUUID().toString(), "[]", "\"10.00\"")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.field").value("seatIds"));

		assertThat(orderRepository.count()).isEqualTo(before);
	}

	@Test
	void duplicateSeatsAreRejectedAndNothingIsRecorded() throws Exception {
		long before = orderRepository.count();

		mockMvc.perform(post("/api/orders")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
								"[\"A1\",\"A1\"]", "\"10.00\"")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.field").value("seatIds"));

		assertThat(orderRepository.count()).isEqualTo(before);
	}

	@Test
	void missingBuyerIsRejectedAndNothingIsRecorded() throws Exception {
		long before = orderRepository.count();

		String json = """
				{"showId":"%s","seatIds":["A1"],"amount":"10.00"}
				""".formatted(UUID.randomUUID());

		mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(json))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.field").value("userId"));

		assertThat(orderRepository.count()).isEqualTo(before);
	}

	@Test
	void missingShowIsRejectedAndNothingIsRecorded() throws Exception {
		long before = orderRepository.count();

		String json = """
				{"userId":"%s","seatIds":["A1"],"amount":"10.00"}
				""".formatted(UUID.randomUUID());

		mockMvc.perform(post("/api/orders").contentType(MediaType.APPLICATION_JSON).content(json))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.field").value("showId"));

		assertThat(orderRepository.count()).isEqualTo(before);
	}

	@Test
	void negativeAmountIsRejectedAndNothingIsRecorded() throws Exception {
		long before = orderRepository.count();

		mockMvc.perform(post("/api/orders")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
								"[\"A1\"]", "\"-1.00\"")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.field").value("amount"));

		assertThat(orderRepository.count()).isEqualTo(before);
	}

	@Test
	void wrongScaleAmountIsRejectedAndNothingIsRecorded() throws Exception {
		long before = orderRepository.count();

		mockMvc.perform(post("/api/orders")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(UUID.randomUUID().toString(), UUID.randomUUID().toString(),
								"[\"A1\"]", "\"10.5\"")))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.field").value("amount"));

		assertThat(orderRepository.count()).isEqualTo(before);
	}

	private static String body(String userId, String showId, String seatIdsJsonArray, String amountJson) {
		return "{\"userId\":\"%s\",\"showId\":\"%s\",\"seatIds\":%s,\"amount\":%s}"
				.formatted(userId, showId, seatIdsJsonArray, amountJson);
	}
}
