package com.marketplace.orders.outbox;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import io.micrometer.tracing.Span;
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
	 *
	 * <p>TRADEOFF: {@code timeout = 30} rather than the 3-second default {@code application.yml} sets
	 * for every other transaction. That default was chosen for the order-acceptance path, where a slow
	 * store should degrade into a fast HTTP refusal (FR-035). This method has a different risk
	 * profile entirely — a single poisoned row failing to send can legitimately take several seconds
	 * on its own (bounded by the producer's {@code max.block.ms}/{@code delivery.timeout.ms},
	 * T095), and a batch can contain more than one such row. Inheriting the 3-second default here
	 * would make the transaction's own commit fail with "transaction timeout expired" on completely
	 * ordinary, already-handled send failures — turning a retryable single-row problem this method's
	 * own try/catch already deals with into a whole-batch abort neither this method nor its caller
	 * asked for. Thirty seconds is generous for a background poll cycle nothing is waiting on
	 * synchronously, while still bounded rather than left to run indefinitely.
	 */
	@Scheduled(fixedDelayString = "${outbox.relay.poll-interval-ms:500}")
	@Transactional(timeout = 30)
	public void pollAndPublish() {
		List<OutboxRecord> claimed = outboxRepository.claimBatch(batchSize);

		// One row's failure must never abandon the rows behind it (guarantee 8) -- so the try/catch
		// below is scoped to a SINGLE row inside this loop, never around the loop as a whole.
		for (OutboxRecord record : claimed) {
			ProducerRecord<String, String> producerRecord = new ProducerRecord<>(
					record.getEventType(), record.getAggregateId().toString(), record.getPayload());

			attachTraceHeaders(record, producerRecord);

			try {
				// .get() is the whole guarantee here: it blocks until the broker has actually
				// acknowledged the message (or thrown), so "marked PUBLISHED" can only ever mean "the
				// broker has it" (guarantee 4). Calling send() and moving on without waiting would
				// let this method mark a row sent that never really left the building.
				kafkaTemplate.send(producerRecord).get();

				record.markPublished(Instant.now());
				metrics.recordPublished();
			} catch (Exception e) {
				record.recordFailure(describeFailure(e));
				metrics.recordSendFailure();

				if (record.getAttempts() >= maxAttempts) {
					record.park();
				}
			}
		}

		// No explicit outboxRepository.save(record) anywhere above: every record in `claimed` is
		// already managed by this transaction's persistence context (claimBatch loaded them), so
		// Hibernate writes back whatever markPublished/recordFailure/park changed when this
		// @Transactional method commits. This is JPA's own "dirty checking" -- it applies to entities
		// already loaded in the current transaction, which is exactly what these are.
	}

	/**
	 * Reconstructs the trace that was active when this row was written, starts a new span
	 * representing this publish, and writes THAT span's context onto the outgoing message as headers
	 * (guarantee 9). A row with nothing stored is left completely untouched — no header is invented
	 * for a context that was never captured (guarantee 10).
	 */
	private void attachTraceHeaders(OutboxRecord record, ProducerRecord<String, String> producerRecord) {
		if (record.getTraceparent() == null) {
			return;
		}

		Map<String, String> stored = new HashMap<>();
		stored.put("traceparent", record.getTraceparent());
		if (record.getTracestate() != null) {
			stored.put("tracestate", record.getTracestate());
		}

		Span publishSpan = propagator.extract(stored, Map::get).name("outbox.publish").start();
		try {
			Map<String, String> outgoing = new HashMap<>();
			propagator.inject(publishSpan.context(), outgoing, Map::put);
			outgoing.forEach((key, value) ->
					producerRecord.headers().add(key, value.getBytes(StandardCharsets.UTF_8)));
		} finally {
			publishSpan.end();
		}
	}

	/** A short, greppable description of why a send failed, for {@code last_error} (guarantee 6). */
	private static String describeFailure(Exception e) {
		Throwable cause = e.getCause() != null ? e.getCause() : e;
		String message = cause.getMessage();
		return cause.getClass().getSimpleName() + (message != null ? ": " + message : "");
	}
}
