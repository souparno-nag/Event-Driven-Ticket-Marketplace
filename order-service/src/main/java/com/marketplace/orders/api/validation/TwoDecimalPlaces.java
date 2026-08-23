package com.marketplace.orders.api.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * A {@link java.math.BigDecimal} whose scale is exactly 2 — the "exactly two decimal places" rule
 * FR-005 requires for a monetary amount.
 *
 * <p>Bean Validation has no built-in constraint for scale; {@code @Digits} bounds the number of
 * digits on each side of the point but does not require a fixed count after it. {@code null} is
 * treated as valid here, matching the convention every Bean Validation constraint follows: a missing
 * value is {@code @NotNull}'s complaint to make, not this one's — combining the two is how a field
 * gets a clean, single-reason error rather than two constraints arguing about the same null.
 */
@Documented
@Constraint(validatedBy = TwoDecimalPlacesValidator.class)
@Target({ ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
public @interface TwoDecimalPlaces {

	String message() default "must have exactly two decimal places";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};
}
