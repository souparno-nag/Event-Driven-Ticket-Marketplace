package com.marketplace.orders.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import org.springframework.test.web.servlet.MvcResult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketplace.orders.RelaySuppressedIT;

/**
 * Specifies the read side of {@code contracts/orders-api.yaml}: {@code GET /api/orders/{orderId}}
 * returns every submitted field unchanged (FR-020, SC-010), an unknown identifier is reported
 * distinctly from a malformed one (FR-021), and each of those two failures carries its own stable
 * problem {@code type} URI rather than sharing one.
 *
 * <p>Deliberately written against raw JSON, the same discipline {@code OrderApiIT} uses for the write
 * side — this is a test of the wire contract, not of {@code OrderView} or any other not-yet-written
 * Java type. Because of that, this file compiles today, before {@code OrderView} (T103) or the GET
 * mapping on {@code OrderController} (T104) exist; every assertion here will FAIL until they do,
 * rather than fail to compile — the intended "red" state for a test written ahead of its
 * implementation.
 */
@AutoConfigureMockMvc
class OrderLookupIT extends RelaySuppressedIT {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Autowired
	private MockMvc mockMvc;

	@Test
	void readingAnAcceptedOrderReturnsEveryFieldUnchanged() throws Exception {
		String userId = UUID.randomUUID().toString();
		String showId = UUID.randomUUID().toString();

		// Submitted already in sorted order deliberately -- OrderView (T103) returns seats sorted for
		// a deterministic response, so this avoids conflating "the response reordered my seats" (an
		// intended, documented normalisation) with "the response changed my seats" (what FR-020
		// actually forbids). Submitting them pre-sorted lets the same jsonPath assertion mean both
		// "arrived correctly" and "still sorted" without needing to special-case the ordering.
		MvcResult accepted = mockMvc.perform(post("/api/orders")
						.contentType(MediaType.APPLICATION_JSON)
						.content(body(userId, showId, "[\"A1\",\"A2\"]", "\"150.00\"")))
				.andExpect(status().isAccepted())
				.andReturn();

		String orderId = MAPPER.readTree(accepted.getResponse().getContentAsString()).get("orderId").asText();

		mockMvc.perform(get("/api/orders/{orderId}", orderId))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.orderId").value(orderId))
				.andExpect(jsonPath("$.userId").value(userId))
				.andExpect(jsonPath("$.showId").value(showId))
				.andExpect(jsonPath("$.seatIds").isArray())
				.andExpect(jsonPath("$.seatIds[0]").value("A1"))
				.andExpect(jsonPath("$.seatIds[1]").value("A2"))
				.andExpect(jsonPath("$.amount").value("150.00"))
				.andExpect(jsonPath("$.status").value("PENDING"))
				.andExpect(jsonPath("$.createdAt").exists())
				.andExpect(jsonPath("$.updatedAt").exists());
	}

	@Test
	void unknownIdentifierIsReportedAsNotFound() throws Exception {
		mockMvc.perform(get("/api/orders/{orderId}", UUID.randomUUID()))
				.andExpect(status().isNotFound())
				.andExpect(header().string("Content-Type", "application/problem+json"))
				.andExpect(jsonPath("$.type").exists())
				.andExpect(jsonPath("$.status").value(404));
	}

	@Test
	void malformedIdentifierIsReportedAsABadRequest() throws Exception {
		mockMvc.perform(get("/api/orders/{orderId}", "not-a-uuid"))
				.andExpect(status().isBadRequest())
				.andExpect(header().string("Content-Type", "application/problem+json"))
				.andExpect(jsonPath("$.type").exists())
				.andExpect(jsonPath("$.status").value(400));
	}

	/**
	 * FR-021's actual requirement, stated directly: an unknown order and a malformed identifier must
	 * be distinguishable BY A CALLER, not merely by two different HTTP status codes a caller might
	 * not even branch on. Comparing the two {@code type} URIs directly is what proves that, rather
	 * than trusting that two tests each asserting "a type exists" happened to exercise two different
	 * ones.
	 */
	@Test
	void notFoundAndMalformedIdentifierUseDistinctProblemTypes() throws Exception {
		MvcResult notFound = mockMvc.perform(get("/api/orders/{orderId}", UUID.randomUUID()))
				.andExpect(status().isNotFound())
				.andReturn();

		MvcResult malformed = mockMvc.perform(get("/api/orders/{orderId}", "not-a-uuid"))
				.andExpect(status().isBadRequest())
				.andReturn();

		JsonNode notFoundBody = MAPPER.readTree(notFound.getResponse().getContentAsString());
		JsonNode malformedBody = MAPPER.readTree(malformed.getResponse().getContentAsString());

		assertThat(notFoundBody.get("type").asText())
				.as("a caller must be able to tell 'no such order' apart from 'that isn't even an id'")
				.isNotEqualTo(malformedBody.get("type").asText());
	}

	private static String body(String userId, String showId, String seatIdsJsonArray, String amountJson) {
		return "{\"userId\":\"%s\",\"showId\":\"%s\",\"seatIds\":%s,\"amount\":%s}"
				.formatted(userId, showId, seatIdsJsonArray, amountJson);
	}
}
