# T138 — `InventoryIT`, the shared test foundation

**What this task did:** wrote `InventoryIT`, an abstract base class that every integration test in
this service (except the ones needing Kafka too) will extend. Extend it, and a test gets a running
PostgreSQL 16, a running Redis 7, and a Spring context correctly wired to both, with all four
migrations already applied — nothing else to arrange.

This is the last piece of Phase 2's foundation, and it's worth explaining not just what it does but
what it deliberately borrows from `order-service`'s own equivalent (`PostgresIT`) and what it has to
add on top, because this service's dependency on Redis makes it genuinely different from anything that
came before it in this project.

---

## The "singleton container" pattern, borrowed wholesale

The reasoning for starting each container exactly once, in a static block, shared by every subclass
for the whole test run, is identical to `order-service`'s own `PostgresIT` and isn't worth re-deriving:
JUnit's `@Testcontainers`/`@Container` annotations tie a container's lifecycle to *one test class*, and
this service already has well over a dozen planned. A container started once per class would mean
starting PostgreSQL and Redis over a dozen times across one build — each one a real, if small, cost
paid on every single run. A `static final` field started in a static initializer is created once, the
first time any subclass is loaded, and Testcontainers' own Ryuk companion container cleans it up when
the JVM exits — including on a crash, which is exactly the scenario hand-written `@AfterAll` cleanup
reliably fails to run for.

## Redis: the part with no precedent to copy

Nothing in this project has talked to Redis before this build step, so this is genuinely new ground,
not a port. Two decisions here are worth being explicit about.

**Why a real Redis rather than a fake one, stated at its sharpest:** the entire property this service
has to be trusted about — that a seat hold is granted or refused as one indivisible act — depends on
something a Java-based in-memory imitation of Redis cannot honestly reproduce: Redis is genuinely
single-threaded, and it genuinely runs a Lua script to completion before serving any other command.
That's not a performance characteristic being tested; it's the actual mechanism the atomicity claim
rests on. A test against a fake would only prove the fake's own locking works, which says nothing
about whether the real one does.

**Why a plain `GenericContainer` rather than a dedicated Testcontainers module:** because none exists.
Postgres and Kafka both have official modules; Redis doesn't. This runs the exact same image —
`redis:7-alpine` — that `infra/docker-compose.yml` runs in the real environment, which matters more
than it might first appear: a test passing against a *different* Redis version than production runs
would be evidence about the wrong thing.

**The wait strategy is more specific than the default for a concrete reason.** Testcontainers' default
readiness check for an exposed port is "can I open a socket to it" — and a process can have its
listening socket bound a moment before it has actually finished initializing enough to answer a real
command. This class waits for Redis's own "Ready to accept connections" log line instead, which is the
identical distinction `infra/docker-compose.yml`'s PING healthcheck makes for the real environment
rather than trusting a bound port alone.

## The one line that had to be right, carried over from an earlier lesson in this build step

```java
registry.add("spring.datasource.url", InventoryIT::jdbcUrlWithSchema);
```

Simply pointing tests at the container's own `getJdbcUrl()` would have reintroduced exactly the bug
T117's own correction commit found and fixed: without `currentSchema=inventory` appended, every native
query in this service — `OutboxRepository.claimBatch`, `ReservationRepository`'s lapsed-seat lookup —
would resolve an unqualified table name against PostgreSQL's `public` schema instead, where none of
this service's tables live. This class appends it the same way `application.yml` does, defensively (a
small helper checks whether the container's own URL already carries a `?` before deciding whether to
append with `?` or `&`, verified directly against what Testcontainers' PostgreSQL module actually
returns today, rather than assumed).

## Shrinking the pool: the same number, the same reasoning, moved here

Order-service's `PostgresIT` shrinks its test pool from a production value of 20 down to 5, because
several distinct Spring test contexts can be alive simultaneously across a growing test suite, and
each one eagerly opens its full connection pool the moment it starts (HikariCP's default minimum-idle
equals its maximum). This service's own production pool is smaller — 12, not 20, from T117's own
budgeting against the shared `max_connections=50` — but the same multiplication risk applies as this
suite grows across the remaining phases, so the same shrink to 5 is applied here, for the same reason.

---

## Verifying it

A temporary concrete subclass (not committed) proved every piece works together: PostgreSQL and Redis
both started, all four migrations applied cleanly against the container's own `inventory` schema, the
seeded "Load Test Hall" show was found through `SeatingPlanRepository` — the real application
repository, not a raw query — and a value was written and read back correctly through a real,
autowired `StringRedisTemplate`. One test, one pass, both containers and the schema-qualification fix
all proven together rather than separately.
