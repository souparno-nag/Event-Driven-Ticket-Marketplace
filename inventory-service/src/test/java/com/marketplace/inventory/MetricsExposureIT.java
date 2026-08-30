package com.marketplace.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.marketplace.inventory.service.ReservationService;

/**
 * FR-045: every one of research.md R13's five meters is genuinely reachable over
 * {@code /actuator/prometheus} — not merely registered in a {@code MeterRegistry} object this test
 * could inspect directly, but actually scraped out over real HTTP the way Prometheus itself would —
 * and the refusal counter carries a distinct {@code cause} tag for each of the three refusal causes.
 *
 * <p>WHY {@code @DynamicPropertySource} re-enables {@code management.defaults.metrics.export.enabled}
 * here — found necessary directly, not assumed: {@code @SpringBootTest} disables metrics export by
 * default (Spring Boot's own convention, so an ordinary test never tries to publish real metrics to an
 * external system), and this session confirmed empirically that the property controlling it
 * ({@code management.defaults.metrics.export.enabled}) is set to {@code false} by Spring Boot's own
 * test machinery at a precedence ABOVE both {@code application.yml} and {@code @TestPropertySource} —
 * both were tried first and both were silently overridden, confirmed by reading the property back from
 * the live {@code Environment} rather than assuming either had taken effect. Only
 * {@code @DynamicPropertySource}, which Spring evaluates last, actually wins.
 *
 * <p>WHY this test triggers one of each outcome before scraping, rather than scraping a freshly
 * started application: {@code inventory.holds.refused} is a TAGGED counter, and Micrometer only
 * materialises one tag combination the first time something is actually recorded against it — a fresh
 * application that has decided nothing yet would not show this meter at all, tags included, which
 * would make this test pass by never actually looking at what it claims to check. Deciding one request
 * per cause first is what makes the scrape afterward a genuine check of what
 * {@code contracts/inventory-consumer.md}'s and {@code DecisionMetrics}' own claims actually produce,
 * not what they merely could produce.
 *
 * <p>Re-declares {@code @SpringBootTest} with a real HTTP port rather than inheriting
 * {@link InventoryIT}'s own mock web environment — Prometheus itself scrapes an actuator endpoint over
 * real HTTP, and this test's whole point is proving that endpoint genuinely answers, not merely that
 * the metrics exist somewhere inside the application context.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MetricsExposureIT extends InventoryIT {

	@LocalServerPort
	int port;

	@Autowired
	TestRestTemplate restTemplate;

	@Autowired
	ReservationService reservationService;

	@Autowired
	JdbcTemplate jdbcTemplate;

	@DynamicPropertySource
	static void enableMetricsExportForThisTest(DynamicPropertyRegistry registry) {
		registry.add("management.defaults.metrics.export.enabled", () -> "true");
	}

	@Test
	void allFiveR13MetersAreScrapableWithTheRefusalCauseTagged() {
		var show = SeatingPlanFixture.provisionShow(jdbcTemplate, "MetricsExposure", 1);
		String seat = show.seatLabels().get(0);

		// One decision per outcome this service can ever produce, so every meter -- and, for the
		// tagged one, every cause -- has actually been recorded at least once before the scrape below.
		reservationService.decide(UUID.randomUUID(), UUID.randomUUID(), List.of("Z99")); // SHOW_NOT_FOUND
		reservationService.decide(UUID.randomUUID(), show.showId(), List.of("ghost-seat")); // SEATS_NOT_FOUND
		reservationService.decide(UUID.randomUUID(), show.showId(), List.of(seat)); // granted
		reservationService.decide(UUID.randomUUID(), show.showId(), List.of(seat)); // SEATS_ALREADY_HELD

		String body = restTemplate.getForObject("http://localhost:" + port + "/actuator/prometheus", String.class);

		assertThat(body).as("granted counter").contains("inventory_holds_granted_total");
		assertThat(body).as("decision duration timer").contains("inventory_decision_duration_seconds");
		assertThat(body).as("dead-lettered counter -- registered eagerly, unlike the tagged one below")
				.contains("inventory_messages_deadlettered_total");

		assertThat(body).as("refused, tagged SHOW_NOT_FOUND")
				.contains("inventory_holds_refused_total{cause=\"SHOW_NOT_FOUND\"");
		assertThat(body).as("refused, tagged SEATS_NOT_FOUND")
				.contains("inventory_holds_refused_total{cause=\"SEATS_NOT_FOUND\"");
		assertThat(body).as("refused, tagged SEATS_ALREADY_HELD")
				.contains("inventory_holds_refused_total{cause=\"SEATS_ALREADY_HELD\"");

		// The outbox relay's own gauge (T130-T133) -- included because R13's list of five names it
		// alongside the four DecisionMetrics owns, and this is the one place all five are checked
		// together as the single set research.md actually specifies.
		assertThat(body).as("outbox backlog age gauge").contains("inventory_outbox_oldest_pending_age");
	}
}
