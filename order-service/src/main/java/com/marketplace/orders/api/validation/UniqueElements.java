package com.marketplace.orders.api.validation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

/**
 * A collection with no duplicate elements — the "must not contain duplicates" half of FR-005's seat
 * list rule. The database's {@code order_seats} composite primary key (V1) makes a duplicate
 * impossible to persist regardless; this constraint is what turns that same mistake into a same-request
 * 400 naming the field, rather than a confusing failure deeper in the write.
 */
@Documented
@Constraint(validatedBy = UniqueElementsValidator.class)
@Target({ ElementType.FIELD, ElementType.RECORD_COMPONENT, ElementType.PARAMETER, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
public @interface UniqueElements {

	String message() default "must not contain duplicate elements";

	Class<?>[] groups() default {};

	Class<? extends Payload>[] payload() default {};
}
