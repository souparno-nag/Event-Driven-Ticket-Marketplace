package com.marketplace.orders.api.validation;

import java.util.Collection;
import java.util.HashSet;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class UniqueElementsValidator implements ConstraintValidator<UniqueElements, Collection<?>> {

	@Override
	public boolean isValid(Collection<?> value, ConstraintValidatorContext context) {
		return value == null || new HashSet<>(value).size() == value.size();
	}
}
