package com.marketplace.inventory.service;

import java.time.Duration;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import com.marketplace.events.RejectionReason;

/**
 * The three meters {@code ReservationService.decide(...)} contributes toward research.md R13's list
 * of five — the other two, {@code inventory.outbox.oldest.pending.age} and
 * {@code inventory.messages.deadlettered}, belong to the outbox relay (already built, T130-T133) and
 * the consumer's dead-letter path (User Story 3) respectively, neither of which this class touches.
 *
 * <p>WHY {@code inventory.holds.refused} is ONE counter tagged by {@code cause} rather than three
 * separate counters (one per {@link RejectionReason}): a fixed, closed set of causes is exactly what a
 * tag is for — every value it can ever take is already enumerated by the enum itself, so there is no
 * risk of the tag's cardinality growing without bound the way it would for something like a raw error
 * message. Three separate counters would also mean three call sites to keep in sync by hand every time
 * a fourth cause is ever added, where one tagged counter needs no change at all — the enum's own
 * {@code name()} becomes the tag value automatically.
 *
 * <p>WHY the tag matters at all, restated concretely rather than left as an assertion: a dashboard
 * showing only a bare {@code inventory.holds.refused} count cannot tell a service that is refusing
 * every request because a show was deleted apart from a service that is behaving completely normally
 * under heavy, legitimate contention. Both look identical as one number climbing. The {@code cause}
 * tag is what lets the two be told apart on the same graph.
 *
 * <p>{@code inventory.decision.duration} is a {@link Timer} covering the WHOLE decision, granted or
 * refused — SC-004's p95 budget applies to the decision as a whole, and SC-020 specifically compares
 * how long a refusal costs against how long a grant costs, which is only answerable if both are
 * recorded under the same meter rather than two different ones that would each need to be found and
 * compared by hand.
 */
@Component
public class DecisionMetrics {

	private final Counter holdsGranted;
	private final Timer decisionDuration;
	private final MeterRegistry meterRegistry;

	public DecisionMetrics(MeterRegistry meterRegistry) {
		this.meterRegistry = meterRegistry;

		this.holdsGranted = Counter.builder("inventory.holds.granted")
				.description("Booking requests granted every seat they asked for")
				.register(meterRegistry);

		this.decisionDuration = Timer.builder("inventory.decision.duration")
				.description("Time to decide a booking request, granted or refused, from request to recorded outcome")
				.register(meterRegistry);
	}

	public void recordGranted() {
		holdsGranted.increment();
	}

	/**
	 * {@code cause} is read from the enum's own {@code name()} rather than a hand-written string, so a
	 * future fourth {@link RejectionReason} value starts appearing on this meter the moment it exists,
	 * with no second place in this class to remember to update.
	 */
	public void recordRefused(RejectionReason cause) {
		meterRegistry.counter("inventory.holds.refused", "cause", cause.name()).increment();
	}

	public void recordDecisionDuration(Duration duration) {
		decisionDuration.record(duration);
	}
}
