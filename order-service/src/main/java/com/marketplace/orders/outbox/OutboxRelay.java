package com.marketplace.orders.outbox;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.tracing.propagation.Propagator;

/**
 * Drains {@code PENDING} outbox rows onto their channels. The saga's first real message reaches
 * {@code order.created} through this class.
 *
 * <p>The one method below is left for you to implement — see {@code contracts/outbox-relay.md} for
 * the full contract, and {@code docs/tasks/T099-outbox-relay-guide.md} for a walk-through of how to
 * approach it. You are done when {@code OutboxRelayIT}, {@code OutboxTracingIT},
 * {@code OutboxConcurrencyIT}, {@code OutboxOrderingIT}, and {@code OutboxRestartRecoveryIT} — every
 * test in Phase 4 of {@code tasks.md} — all pass.
 */
@Component
public class OutboxRelay {

	private final OutboxRepository outboxRepository;
	private final KafkaTemplate<String, String> kafkaTemplate;
	private final Propagator propagator;
	private final OutboxMetrics metrics;
	private final int batchSize;
	private final int maxAttempts;

	public OutboxRelay(
			OutboxRepository outboxRepository,
			KafkaTemplate<String, String> kafkaTemplate,
			Propagator propagator,
			OutboxMetrics metrics,
			@Value("${outbox.relay.batch-size:100}") int batchSize,
			@Value("${outbox.relay.max-attempts:5}") int maxAttempts) {
		this.outboxRepository = outboxRepository;
		this.kafkaTemplate = kafkaTemplate;
		this.propagator = propagator;
		this.metrics = metrics;
		this.batchSize = batchSize;
		this.maxAttempts = maxAttempts;
	}

	/**
	 * TODO(developer): implement this method. It is the one piece of build step 2 you write by hand —
	 * see the spec's Clarifications section for why, and {@code contracts/outbox-relay.md} for exactly
	 * what it must guarantee.
	 *
	 * <p>What you have to work with, all already wired above:
	 *
	 * <ul>
	 *   <li>{@code outboxRepository.claimBatch(batchSize)} — returns the rows YOU now own exclusively
	 *       for the rest of this transaction, already in the order you must publish them in (T094).
	 *   <li>{@code kafkaTemplate} — configured with {@code acks=all} and an idempotent producer
	 *       (T095). {@code kafkaTemplate.send(topic, key, value)} returns a
	 *       {@code CompletableFuture} — you must wait for it, not merely start it.
	 *   <li>{@code propagator} — injects a stored trace context into a Kafka message's headers.
	 *   <li>{@code metrics} — call {@code metrics.recordPublished()} and
	 *       {@code metrics.recordSendFailure()} at the right moments; the two gauges need no calls
	 *       from you at all.
	 *   <li>{@code maxAttempts} — how many failed attempts a row gets before you park it.
	 * </ul>
	 *
	 * <p>The twelve guarantees this method must provide, restated from {@code contracts/outbox-relay.md}:
	 *
	 * <ol>
	 *   <li>Every claimed row is sent to the channel named by its {@code event_type}.
	 *   <li>The Kafka message key is the row's {@code aggregate_id}.
	 *   <li>The message value is the stored {@code payload}, sent verbatim — never re-serialized.
	 *   <li>A row is marked {@code PUBLISHED}, with {@code published_at} set, only AFTER the broker
	 *       acknowledges — never on the strength of having merely called {@code send()}.
	 *   <li>A row already {@code PUBLISHED} is never sent again.
	 *   <li>On send failure the row stays {@code PENDING}; {@code attempts} increments and
	 *       {@code last_error} records why.
	 *   <li>When {@code attempts} reaches {@code maxAttempts} the row becomes {@code PARKED} and is
	 *       never retried again.
	 *   <li>One row's failure does not abandon the other rows in this same batch.
	 *   <li>The trace context stored on a row is injected into that message's outgoing headers.
	 *   <li>A row with no stored trace context is still sent — untraced, without error.
	 *   <li>Two relays running concurrently never send the same row (mostly guaranteed by
	 *       {@code claimBatch} itself — but sending asynchronously without awaiting the outcome
	 *       before moving on can still undermine it).
	 *   <li>Rows for one order reach the channel in the order they were recorded (also mostly
	 *       {@code claimBatch}'s guarantee — broken only by not processing claimed rows in the order
	 *       they were returned).
	 * </ol>
	 *
	 * <p>WHY {@code @Transactional} here is load-bearing, not decorative: the row locks
	 * {@code claimBatch}'s {@code FOR UPDATE} takes are held for exactly as long as this transaction
	 * is open. Without this annotation, the locks would be released the instant the query returned —
	 * before a single message had even been sent — and guarantee 11 would evaporate silently, with no
	 * exception anywhere to say so.
	 *
	 * <p>WHY {@code fixedDelayString}, not {@code fixedRate}: a fixed delay is measured from the END
	 * of one run to the START of the next, so a run that takes longer than the interval can never
	 * overlap the one behind it. A fixed rate would start runs on a strict clock regardless of whether
	 * the previous one had finished.
	 */
	@Scheduled(fixedDelayString = "${outbox.relay.poll-interval-ms:500}")
	@Transactional
	public void pollAndPublish() {
		// TODO(developer)
	}
}
