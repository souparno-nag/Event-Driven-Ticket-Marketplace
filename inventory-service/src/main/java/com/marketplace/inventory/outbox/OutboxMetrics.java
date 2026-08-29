package com.marketplace.inventory.outbox;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * The outbox's own four meters, ported from order-service (research.md R8) and retagged with an
 * {@code inventory.} prefix rather than order-service's bare {@code outbox.*} names.
 *
 * <p>WHY retag rather than port unchanged: two services now expose an outbox, and Prometheus
 * disambiguates them by job and instance labels regardless of the metric name — but a shared
 * dashboard or an ad hoc query naming {@code outbox_records_published_total} directly would silently
 * mean "whichever service happens to be selected", which is a mistake waiting to be made by whoever
 * builds that dashboard months from now. Prefixing with the owning service's name is the cheap fix,
 * paid once here rather than discovered as a confusing graph later. {@code inventory.outbox.oldest.pending.age}
 * additionally matches the exact name research.md R13 gives it, since that meter is also called out
 * by name in this service's own metrics decision, alongside the ones {@code DecisionMetrics} (a later
 * task) will add.
 *
 * <p>Everything else about this class — what each meter measures and why — is unchanged from
 * order-service, because none of it is specific to which two message types the outbox carries.
 *
 * <p>Two counters — {@code recordPublished()} and {@code recordSendFailure()} — are the relay's own
 * job to call, once per row, at the point each outcome actually happens. The two gauges need no calls
 * at all: they read the database directly on every Prometheus scrape.
 *
 * <p>WHY the gauges measure backlog AGE rather than backlog DEPTH, which sounds like the more obvious
 * choice: depth spikes harmlessly the instant a burst of orders arrives and says nothing about
 * whether the relay is keeping up — a hundred rows that all arrived a second ago and a hundred rows
 * that have sat there for an hour look identical to a meter counting rows. A rising oldest-pending
 * AGE is what actually means the relay is losing ground, because it can only grow if rows are being
 * added faster than the oldest one is being cleared.
 *
 * <p>WHY the gauges query the database on every scrape rather than caching a value the relay updates
 * as it runs: a cached value reports whatever the relay last observed, which keeps looking healthy for
 * a while even after the relay has stopped entirely. Reading the database directly is what makes the
 * gauge honest about the CURRENT state rather than the state as of the relay's last successful run —
 * and it is cheap, since both queries run against the partial indexes {@code idx_outbox_pending} and
 * {@code idx_outbox_parked} (V4), whose whole purpose is keeping this kind of question fast regardless
 * of how large the table grows.
 */
@Component
public class OutboxMetrics {

	private final JdbcTemplate jdbc;
	private final Counter recordsPublished;
	private final Counter sendFailures;

	public OutboxMetrics(JdbcTemplate jdbc, MeterRegistry meterRegistry) {
		this.jdbc = jdbc;

		this.recordsPublished = Counter.builder("inventory.outbox.records.published")
				.description("Outbox rows successfully published to their channel")
				.register(meterRegistry);

		this.sendFailures = Counter.builder("inventory.outbox.send.failures")
				.description("Failed send attempts, whether the row was retried or ultimately parked")
				.register(meterRegistry);

		Gauge.builder("inventory.outbox.records.parked", this, OutboxMetrics::readParkedCount)
				.description("Rows that have exhausted their attempts and stopped being retried")
				.register(meterRegistry);

		Gauge.builder("inventory.outbox.oldest.pending.age", this, OutboxMetrics::readOldestPendingAgeSeconds)
				.description("Age in seconds of the oldest unsent row -- the meter that actually says whether the relay is keeping up")
				.register(meterRegistry);
	}

	public void recordPublished() {
		recordsPublished.increment();
	}

	public void recordSendFailure() {
		sendFailures.increment();
	}

	private double readParkedCount() {
		Long count = jdbc.queryForObject("SELECT count(*) FROM outbox WHERE status = 'PARKED'", Long.class);
		return count == null ? 0 : count;
	}

	private double readOldestPendingAgeSeconds() {
		// EXTRACT(EPOCH FROM ...) turns a PostgreSQL interval into a plain number of seconds, which
		// is what a Gauge needs -- Micrometer gauges hold a double, not a java.time type.
		Double seconds = jdbc.queryForObject(
				"SELECT EXTRACT(EPOCH FROM (now() - min(created_at))) FROM outbox WHERE status = 'PENDING'",
				Double.class);
		// No PENDING rows at all is the healthy, common case, and MIN() over zero rows is SQL NULL --
		// not a database problem to raise, just "there is currently no backlog to measure the age of."
		return seconds == null ? 0 : seconds;
	}
}
