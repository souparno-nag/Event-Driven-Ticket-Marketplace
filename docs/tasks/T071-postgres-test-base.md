# T071 — A real database for tests

**What this task did:** wrote the base class that gives an integration test a running PostgreSQL,
a Spring context wired to it, and both migrations already applied.

Extend it and everything is arranged:

```java
class SomethingIT extends PostgresIT {
    @Autowired OrderRepository orders;
    // a real PostgreSQL 16 is running, V1 and V2 have been applied
}
```

---

## Testcontainers: a real database, thrown away afterwards

**Testcontainers** is a library that starts Docker containers from Java code. Ask for PostgreSQL and
it pulls the image, starts it, waits until it is genuinely accepting connections, and tells you which
port it landed on.

The usual alternative is an **in-memory database** such as H2 — fast, no Docker, and pretending to be
PostgreSQL. It was rejected deliberately here, because nearly everything this service must be trusted
about is behaviour PostgreSQL provides and an imitation does not:

- `FOR UPDATE SKIP LOCKED`, which is how the relay claims rows without two copies colliding
- **partial indexes**, which keep the relay's cost proportional to the backlog rather than the table
- the `jsonb` column type
- `CHECK` constraints written as boolean equalities

A test suite against H2 would pass, and would be answering questions about H2.

---

## One container for the whole run

```java
static final PostgreSQLContainer<?> POSTGRES =
        new PostgreSQLContainer<>(DockerImageName.parse("postgres:16-alpine"));

static {
    POSTGRES.start();
}
```

Testcontainers offers annotations — `@Testcontainers` on the class and `@Container` on the field —
that tie a container's lifetime to one test class. Those are not used here.

The reason is arithmetic. Phase 3 and Phase 4 add roughly ten integration test classes. With the
annotations, PostgreSQL starts and stops **ten times**, about a second each, on every single build.
A plain `static` field in a shared base class is initialised once when the class first loads, and
every subclass afterwards reuses it. This is the documented "singleton container" pattern.

**Nothing shuts it down, and that is fine.** Testcontainers starts a small companion container called
**Ryuk** whose only job is to watch this JVM and delete the containers it created when the JVM exits
— including when a test crashes, which is exactly the case where hand-written cleanup code does not
run. Without something like it, a few failed runs leave a machine full of orphaned databases.

**The trade-off, stated plainly:** one shared database means tests see each other's rows. That is a
real cost and it is accepted, because per-class isolation is paid for on every build forever. The
discipline it demands is that each test uses its own generated identifiers — a test asserting "there
is exactly one order in the table" is asserting something about the entire suite, not about itself.

## Wiring the context to the container

```java
@DynamicPropertySource
static void datasourceProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    ...
}
```

The connection URL cannot be written in a properties file, because the port is not known until the
container starts. Testcontainers binds a **random free port** on purpose, so a test run never
collides with a PostgreSQL the developer already has listening on 5432.

`@DynamicPropertySource` is Spring's answer: it runs while the context is being built and can supply
values computed at that moment. The values are passed as **method references** rather than strings,
so Spring calls them at the point it needs them.

Only the datasource is overridden. Flyway, `ddl-auto: validate`, and the relay settings all come from
the real `application.yml` — so these tests exercise the configuration the service actually ships
with, not a test-only variant of it. A test-only configuration proves the test-only configuration
works.

## No Kafka here

This base starts PostgreSQL and nothing else, and that is a design decision rather than an
omission.

User Story 1 — accepting an order and writing its outbox row atomically — is complete without a
broker. Its tests should not pay several seconds per run for a component they never touch. Tests that
genuinely need Kafka will extend a different base, `KafkaPostgresIT`, arriving in T088.

---

## Not yet proven

This class compiles. Nothing has run against it, because no test extends it yet. **T072** writes that
first test and is where the schema from T062/T063 and the entities from T066/T067 are finally shown
to agree with each other.
