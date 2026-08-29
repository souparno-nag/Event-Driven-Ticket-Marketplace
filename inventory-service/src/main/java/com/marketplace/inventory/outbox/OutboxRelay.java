package com.marketplace.inventory.outbox;

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
 * Drains {@code PENDING} outbox rows onto their channels — {@code seats.reserved} or
 * {@code seats.rejected}, this service's half of the saga's second hop.
 *
 * <p>Ported from order-service's {@code OutboxRelay} (research.md R8, R11) with
 * {@code pollAndPublish} fully IMPLEMENTED here, not re-stubbed. This method was the developer
 * exercise for build step 2 — worked out once, reviewed, and proven correct by
 * {@code OutboxRelayIT}, {@code OutboxTracingIT}, {@code OutboxConcurrencyIT},
 * {@code OutboxOrderingIT}, and {@code OutboxRestartRecoveryIT} in order-service. Repeating that
 * exercise here would teach nothing a second time; the developer exercises for THIS build step are
 * the two Lua scripts in {@code seats/}, which are genuinely new problems this codebase has not
 * solved before. {@code OutboxRelayPortIT} (a later task) proves this port works in this module,
 * without re-proving all twelve guarantees a second time — that exhaustive suite already lives
 * against identical code in order-service.
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
	 * The twelve guarantees this method provides, restated from {@code contracts/outbox-relay.md}
	 * (reused verbatim from step 2 per {@code contracts/README.md} — its aggregate id is still the
	 * order id and its two event types are now {@code seats.reserved} and {@code seats.rejected}):
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
	 * overlap the one behind it.
	 *
	 * <p>TRADEOFF: {@code timeout = 30} rather than the 3-second default {@code application.yml} sets
	 * for the reservation decision path. That default fits a request a buyer is waiting on, where a
	 * slow store should degrade into a fast refusal. This method has a different risk profile — a
	 * single poisoned row failing to send can legitimately take several seconds on its own (bounded by
	 * the producer's {@code max.block.ms}/{@code delivery.timeout.ms}), and a batch can contain more
	 * than one such row. Inheriting the 3-second default here would fail the transaction's own commit
	 * on completely ordinary, already-handled send failures.
	 */
	@Scheduled(
			fixedDelayString = "${outbox.relay.poll-interval-ms:500}",
			initialDelayString = "${outbox.relay.initial-delay-ms:0}")
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
		// @Transactional method commits. This is JPA's own "dirty checking".
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
