package com.marketplace.orders.api;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * The {@code orders.refused.capacity} counter (R12), incremented whenever {@link ApiExceptionHandler}
 * refuses a request as a capacity problem rather than a bad one.
 *
 * <p>WHY this is a separate class rather than a counter built inline in the exception handler:
 * {@code MeterRegistry} auto-registers whatever meter names are requested, so a typo in a metric
 * name anywhere it is incremented would silently create a second, wrong meter rather than fail
 * loudly. One class owning the meter's construction is what makes the name a single point of truth
 * (FR-036).
 */
@Component
public class CapacityMetrics {

	private final Counter capacityRefusals;

	public CapacityMetrics(MeterRegistry meterRegistry) {
		this.capacityRefusals = Counter.builder("orders.refused.capacity")
				.description("Booking requests refused because the service was at capacity")
				.register(meterRegistry);
	}

	public void recordCapacityRefusal() {
		capacityRefusals.increment();
	}
}
