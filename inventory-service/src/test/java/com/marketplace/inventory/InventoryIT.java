package com.marketplace.inventory;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for integration tests that need real PostgreSQL and real Redis, and nothing else.
 *
 * <p>Extend this and the test gets a running PostgreSQL 16, a running Redis 7, a Spring context wired
 * to both, and all four Flyway migrations already applied. Nothing else has to be arranged.
 *
 * <p>WHY real Redis rather than an embedded fake — the reasoning that matters most in this service,
 * restated from {@code pom.xml}'s own note on this dependency: script ATOMICITY is the property under
 * test throughout this build step (FR-039). Redis is single-threaded and runs a Lua script to
 * completion before serving any other command, which is the entire mechanism that makes an
 * all-or-nothing seat hold correct rather than racy. A Java-based in-memory stand-in for Redis would
 * answer questions about the stand-in's own locking semantics, not about the actual guarantee this
 * service depends on. The same reasoning {@code PostgresIT} in order-service gives for a real
 * PostgreSQL over H2 — {@code FOR UPDATE SKIP LOCKED}, partial indexes, {@code jsonb} — applies here
 * to Redis for exactly the same category of reason: an imitation answers questions about itself.
 *
 * <p>WHY no official Testcontainers module is used for Redis: none exists. Postgres and Kafka each
 * have one; Redis does not, so this class runs a plain {@link GenericContainer} against the same
 * {@code redis:7-alpine} image {@code infra/docker-compose.yml} runs — the identical image the real
 * environment uses, not a coincidentally similar one.
 *
 * <p>WHY no Kafka here: User Story 1 — deciding a booking outcome and taking or refusing a hold — is
 * completely testable by calling {@code ReservationService} directly, and must be testable without a
 * broker (research.md R10): the channel caps genuinely simultaneous work at three partitions, which
 * would let a broken all-or-nothing hold pass a contention test by luck rather than by correctness.
 * Starting Kafka for tests that never touch it would also add several seconds to every run for a
 * component none of them exercise. Tests that genuinely need a broker extend {@link InventoryKafkaIT}
 * instead.
 */
@SpringBootTest
public abstract class InventoryIT {

	/**
	 * One container for the entire test run, shared by every subclass — the same "singleton
	 * container" pattern order-service's own {@code PostgresIT} uses, for the identical reason: JUnit's
	 * {@code @Testcontainers}/{@code @Container} annotations tie a container's lifecycle to one test
	 * class, and this service has well over a dozen. A static field started once on first class-load
	 * is reused by everything afterwards; Testcontainers' own Ryuk companion container removes it when
	 * this JVM exits, including on a crash, which is exactly the case hand-written cleanup reliably
	 * fails to run for.
	 */
	static final PostgreSQLContainer<?> POSTGRES =
			new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

	/**
	 * Same image, same lifecycle reasoning as {@link #POSTGRES}. The log-message wait is deliberately
	 * more specific than the default "wait for the port to open": a Redis process can have its
	 * listening socket bound a moment before it has actually finished loading and is ready to serve a
	 * command, and this is the same distinction {@code infra/docker-compose.yml}'s own PING healthcheck
	 * exists to make for the real environment.
	 */
	static final GenericContainer<?> REDIS =
			new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
					.withExposedPorts(6379)
					.waitingFor(Wait.forLogMessage(".*Ready to accept connections.*\\n", 1));

	static {
		POSTGRES.start();
		REDIS.start();
	}

	/**
	 * Points the Spring context at both containers.
	 *
	 * <p>This has to happen at runtime rather than in a properties file, because neither container's
	 * port is known until it starts — Testcontainers deliberately binds a random free port so a test
	 * run never collides with a PostgreSQL or Redis the developer already has running locally.
	 *
	 * <p>{@code currentSchema=inventory} is appended to the JDBC URL exactly as {@code application.yml}
	 * does in production (T117): dropping it here would silently resolve every native query — this
	 * service's own {@code claimBatch} and {@code findLapsedReservationsCoveringSeats} among them —
	 * against PostgreSQL's default {@code public} schema instead, which is precisely the bug that
	 * property fixes and which a test suite that dropped it would never catch.
	 *
	 * <p>Only the datasource and Redis connection are overridden. Everything else — Flyway,
	 * {@code ddl-auto: validate}, the schema settings, the relay settings — comes from the real
	 * {@code application.yml}, so these tests exercise the configuration the service actually ships
	 * with rather than a test-only variant of it.
	 */
	@DynamicPropertySource
	static void containerProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", InventoryIT::jdbcUrlWithSchema);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);

		registry.add("spring.data.redis.host", REDIS::getHost);
		registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
	}

	/**
	 * Appends {@code currentSchema=inventory} correctly whether or not the container's own URL
	 * already carries a query string — verified directly, Testcontainers' PostgreSQL URL currently
	 * includes {@code ?loggerLevel=OFF} by default, but hard-coding {@code &} against that assumption
	 * would silently break the moment a Testcontainers upgrade changes it.
	 */
	private static String jdbcUrlWithSchema() {
		String url = POSTGRES.getJdbcUrl();
		return url + (url.contains("?") ? "&" : "?") + "currentSchema=inventory";
	}

	/**
	 * Read lazily by {@link #shrinkPoolForTests}, rather than a literal {@code "5"} in that method's
	 * own body, specifically so {@link HighConcurrencyIT} can override it. Verified directly, not
	 * assumed: a subclass's own {@code @DynamicPropertySource} method registering the identical
	 * property key does NOT win over this class's registration — Spring collects every
	 * {@code @DynamicPropertySource} method up the whole hierarchy and a later registration for the
	 * same key does not evict an earlier one, the same behaviour order-service's own
	 * {@code PostgresIT} documents hitting for the identical reason. A mutable static field this
	 * class's own supplier reads at evaluation time, rather than at registration time, sidesteps that
	 * entirely: whichever value the field holds by the time Spring actually calls the supplier is the
	 * value used, regardless of which class in the hierarchy physically registered it. A subclass's
	 * static initialiser — which the JVM runs before Spring ever builds the context — is what sets it.
	 */
	protected static int poolSize = 5;

	/**
	 * Shrinks the pool every test context opens, from the production value of 12 down to
	 * {@link #poolSize} — 5 by default, the same number order-service's own {@code PostgresIT}
	 * shrinks to, and for the same underlying reason: several distinct Spring test contexts can be
	 * cached and alive at once across this service's growing test suite, each eagerly opening its
	 * full pool on startup since HikariCP's default minimum-idle equals its maximum-pool-size.
	 * Overriding it here, once, on the one class every test context already shares, keeps every
	 * combination of contexts this suite can form comfortably under PostgreSQL's connection limit
	 * without touching production's own pool size — which is itself a deliberate admission-control
	 * choice (R12) this override must not disturb. {@link HighConcurrencyIT} raises the field's value
	 * for the handful of tests that genuinely need more than 5 connections at once.
	 */
	@DynamicPropertySource
	static void shrinkPoolForTests(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.hikari.maximum-pool-size", () -> String.valueOf(poolSize));
	}
}
