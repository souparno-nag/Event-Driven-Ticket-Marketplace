package com.marketplace.orders;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for integration tests that need a real PostgreSQL and nothing else.
 *
 * <p>Extend this and the test gets a running PostgreSQL 16, a Spring context wired to it, and both
 * Flyway migrations already applied. Nothing else has to be arranged.
 *
 * <p>WHY a real database rather than an in-memory one such as H2: almost everything this service has
 * to be trusted about is behaviour PostgreSQL provides and an imitation does not — {@code FOR UPDATE
 * SKIP LOCKED} row claiming, partial indexes, the {@code jsonb} column type, and CHECK constraints
 * with boolean-equality expressions. A test against H2 would faithfully answer questions about H2.
 *
 * <p>WHY no Kafka here: User Story 1 — accepting an order and recording its outbox row atomically —
 * is complete without a broker, and must be testable without one. Starting Kafka for those tests
 * would add several seconds to every run for a component none of them touch. Tests that genuinely
 * need a broker extend {@code KafkaPostgresIT} instead, which arrives in T088.
 */
@SpringBootTest
public abstract class PostgresIT {

	/**
	 * One container for the entire test run, shared by every subclass.
	 *
	 * <p>This is the "singleton container" pattern, and it is used rather than JUnit's
	 * {@code @Testcontainers}/{@code @Container} annotations deliberately. Those tie a container's
	 * lifecycle to one test class, so a suite of six integration tests would start and stop
	 * PostgreSQL six times — roughly a second each, paid on every build. A static field started once
	 * is started on first class-load and reused by everything afterwards.
	 *
	 * <p>Nothing stops it. Testcontainers launches a small companion container called Ryuk which
	 * watches this JVM and removes the containers it created when the JVM exits — including when a
	 * test crashes, which is the case where hand-written cleanup reliably fails to run.
	 *
	 * <p>TRADEOFF: one shared database means tests see each other's rows. That is a real cost and is
	 * accepted, because the alternative — a container per class — buys isolation at a price paid on
	 * every build forever. Tests are expected to use their own generated identifiers rather than
	 * assuming an empty table; a test asserting "there is exactly one order" is asserting something
	 * about the whole suite, not about itself.
	 */
	static final PostgreSQLContainer<?> POSTGRES =
			new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

	static {
		POSTGRES.start();
	}

	/**
	 * Points the Spring context at the container.
	 *
	 * <p>This has to happen at runtime rather than in a properties file, because the port is not
	 * known until the container starts — Testcontainers deliberately binds a random free port so
	 * that a test run never collides with a PostgreSQL the developer already has running on 5432.
	 *
	 * <p>The values are supplied as method references rather than as strings. Spring calls them after
	 * the container is up, which is what makes the ordering work: this method is invoked while the
	 * context is being built, and the static initialiser above has already run by then.
	 *
	 * <p>Only the datasource is overridden. Everything else — Flyway, {@code ddl-auto: validate}, the
	 * relay settings — comes from the real {@code application.yml}, so these tests exercise the
	 * configuration the service actually ships with rather than a test-only variant of it.
	 */
	@DynamicPropertySource
	static void datasourceProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		registry.add("spring.datasource.username", POSTGRES::getUsername);
		registry.add("spring.datasource.password", POSTGRES::getPassword);
	}

	/**
	 * Shrinks the pool every test context opens, from the production value of 20 down to 5.
	 *
	 * <p>Found by running Phase 3 and Phase 4 together and hitting {@code FATAL: sorry, too many
	 * clients already} even after {@link com.marketplace.orders.outbox.RelayDrivenIT} cut four
	 * duplicate contexts down to one shared one: Spring's test context cache still keys on which
	 * class declares a {@code @DynamicPropertySource}, so this suite genuinely runs several distinct
	 * cached contexts at once — one for plain {@code PostgresIT} subclasses, one for
	 * {@code KafkaPostgresIT}'s own (the one Phase 4 class that must keep the real scheduling
	 * interval), one for {@code RelayDrivenIT}'s four, and one more for the {@code @MockBean} nested
	 * class in {@code OrderAcceptanceIT} (a mocked bean still gets its own distinct context). Each
	 * eagerly opens its full pool on startup, since HikariCP's default minimum-idle equals its
	 * maximum-pool-size. Four contexts at the production size of 20 is 80 connections, uncomfortably
	 * close to (and in practice enough to tip over) this container's actual default of 100 — the
	 * {@code max_connections=50} figure elsewhere in this codebase is what production's docker-compose
	 * sets for its own PostgreSQL; this Testcontainers instance runs unmodified, so it defaults to 100,
	 * not 50. Overriding it here, once, on the one class every test context already shares, keeps every
	 * combination of contexts this suite can form comfortably under that limit without touching
	 * production's own pool size, which is a deliberate admission-control choice (R5, FR-035) this
	 * override must not disturb.
	 *
	 * <p>{@code OrderCapacityIT} still passes unmodified: it reads {@code getMaximumPoolSize()} off the
	 * live {@code HikariDataSource} rather than assuming 20, precisely so a change like this one
	 * wouldn't invalidate it.
	 */
	@DynamicPropertySource
	static void shrinkPoolForTests(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.hikari.maximum-pool-size", () -> "5");
	}
}
