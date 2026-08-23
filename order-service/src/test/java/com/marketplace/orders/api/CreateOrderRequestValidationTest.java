package com.marketplace.orders.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;

/**
 * Specifies {@link CreateOrderRequest}'s validation contract (FR-005), before that class exists.
 *
 * <p>Deliberately agnostic about HOW validation is implemented — no reference to any constraint
 * annotation, custom or built-in. It constructs a {@code CreateOrderRequest} and asks a standard
 * {@link Validator} what is wrong with it, which is exactly the question the eventual
 * {@code ApiExceptionHandler} (T084) will be answering for a real HTTP request. Whether the
 * implementation uses a hand-rolled {@code @UniqueElements} constraint or something else is not this
 * test's business; only the outcome — which field is named — is.
 *
 * <p>Will not compile until {@code CreateOrderRequest} exists (T079). That is the intended state:
 * this file is the specification the developer of T079 must satisfy, and in a statically typed
 * language "does not compile yet" is the strongest possible form of "fails before the implementation
 * exists".
 */
class CreateOrderRequestValidationTest {

	private static ValidatorFactory factory;
	private static Validator validator;

	@BeforeAll
	static void createValidator() {
		factory = Validation.buildDefaultValidatorFactory();
		validator = factory.getValidator();
	}

	@AfterAll
	static void closeValidatorFactory() {
		factory.close();
	}

	@Test
	void acceptsAWellFormedRequest() {
		CreateOrderRequest request = new CreateOrderRequest(
				UUID.randomUUID(), UUID.randomUUID(), List.of("A1", "A2"), new BigDecimal("150.00"));

		assertThat(validator.validate(request)).isEmpty();
	}

	@Test
	void rejectsAnEmptySeatList() {
		CreateOrderRequest request = new CreateOrderRequest(
				UUID.randomUUID(), UUID.randomUUID(), List.of(), new BigDecimal("25.00"));

		assertThat(propertyPathsOf(validator.validate(request))).contains("seatIds");
	}

	@Test
	void rejectsDuplicateSeats() {
		CreateOrderRequest request = new CreateOrderRequest(
				UUID.randomUUID(), UUID.randomUUID(), List.of("A1", "A1"), new BigDecimal("25.00"));

		assertThat(propertyPathsOf(validator.validate(request))).contains("seatIds");
	}

	@Test
	void rejectsAMissingBuyer() {
		CreateOrderRequest request = new CreateOrderRequest(
				null, UUID.randomUUID(), List.of("A1"), new BigDecimal("25.00"));

		assertThat(propertyPathsOf(validator.validate(request))).contains("userId");
	}

	@Test
	void rejectsAMissingShow() {
		CreateOrderRequest request = new CreateOrderRequest(
				UUID.randomUUID(), null, List.of("A1"), new BigDecimal("25.00"));

		assertThat(propertyPathsOf(validator.validate(request))).contains("showId");
	}

	@Test
	void rejectsANegativeAmount() {
		CreateOrderRequest request = new CreateOrderRequest(
				UUID.randomUUID(), UUID.randomUUID(), List.of("A1"), new BigDecimal("-1.00"));

		assertThat(propertyPathsOf(validator.validate(request))).contains("amount");
	}

	@Test
	void rejectsAnAmountWithTheWrongNumberOfDecimalPlaces() {
		// Scale 1, not the required 2 -- "25.5" rather than "25.50".
		CreateOrderRequest request = new CreateOrderRequest(
				UUID.randomUUID(), UUID.randomUUID(), List.of("A1"), new BigDecimal("25.5"));

		assertThat(propertyPathsOf(validator.validate(request))).contains("amount");
	}

	private static Set<String> propertyPathsOf(Set<ConstraintViolation<CreateOrderRequest>> violations) {
		return violations.stream()
				.map(v -> v.getPropertyPath().toString())
				.collect(java.util.stream.Collectors.toSet());
	}
}
