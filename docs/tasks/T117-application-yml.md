# T117 — Configuring `inventory-service`

**What this task did:** wrote `inventory-service/src/main/resources/application.yml`, telling the
service how to reach PostgreSQL, Redis and Kafka, and setting the properties its own code will read
by name in later tasks. Removed the generated placeholder `application.properties`, so there is
exactly one file the service reads its configuration from.

Nothing here makes anything *happen* yet — no class in the module reads most of these properties.
This is the wiring diagram; T126 onward are what plugs into it.

---

## Why a whole file for settings that do nothing yet

`order-service`'s `application.yml` (T060) is the direct model for this one, and reading the two side
by side is the fastest way to see what is actually new. Three things distinguish this file from a
copy-paste of that one: a schema this service must ask for that `order-service` never had to, a
second store, and a startup-ordering switch whose absence looks perfectly healthy while it double-books.

---

## The datasource: the same database, a different room

```yaml
datasource:
  url: jdbc:postgresql://localhost:5432/marketplace
```

Same URL as `order-service`. Same database, same container, same credentials. What differs is what
comes next:

```yaml
jpa:
  hibernate:
    default-schema: inventory
flyway:
  schemas: inventory
  default-schema: inventory
```

`infra/docker-compose.yml` provisions **one** PostgreSQL database. `order-service` has been using it
as if it owned the whole thing, because until now nothing else touched it. The moment a second
service starts running its own Flyway migrations against the same database, both services need
somewhere of their own to keep house — otherwise they fight over one `flyway_schema_history` table,
and whichever starts second finds a migration history that is not its own and fails validation.

A PostgreSQL **schema** is a namespace inside one database — think of it as a folder. Giving
`inventory-service` the `inventory` schema means it gets its own tables, its own migration history,
and its own answer to "what does the schema look like right now", all without touching
`order-service`'s tables, without a second database, and without any change to `infra/`. Both
`spring.jpa.hibernate.default-schema` and the two Flyway properties have to be set for the same
reason from two different angles: Flyway needs to know where to create and track its own migrations,
and Hibernate needs to know where to look for the tables Flyway created, when it validates the
entities against them.

Everything else in this block — the 250ms connection timeout, the `statement_timeout` — is identical
reasoning to `order-service`'s, copied rather than reinvented, because it is the same problem: a
caller waiting on an exhausted pool should be told promptly rather than joining a wall of requests all
timing out together, and a query stuck inside the database needs the database itself to cut it off,
because a client-side timeout cannot interrupt a socket it is still waiting on.

**One number does differ, and it is worth knowing why.** `order-service` allows 20 connections;
this file allows 12. PostgreSQL here has `max_connections=50` total (`infra/docker-compose.yml`), and
that is a single shared budget across every service that will eventually exist. 20 for order-service
plus 12 for this one leaves room for payment-service and projection-service to arrive in steps 4 and 6
without anyone having to go back and enlarge the container. Choosing a number here is choosing it for
services that do not exist yet.

---

## Redis: the fast store, and the one number that matters most

```yaml
data:
  redis:
    host: localhost
    port: 6379
    timeout: 1s
```

This is the first time any service in the project talks to Redis. Three lines suffice because there
is not much to configure — no schema, no migration history, just a host and a port.

The one setting worth pausing on is `timeout: 1s`. This is a *command* timeout: if Redis does not
answer within a second, the client gives up and raises an exception rather than waiting indefinitely.

Why that number, specifically, matters here more than it would in most services: this project's
constitution (Principle IV) forbids an event handler from performing an unbounded-latency operation
inline in its processing path. The Redis call in this service's booking decision is exactly such an
operation — it happens synchronously, inside the transaction that decides a buyer's fate, on every
single request. Without a bound, a Redis that has gone quiet (not crashed — just slow) would hang the
consumer thread indefinitely, and the whole reason this project has a "the stores are unreachable"
failure mode at all (FR-047) is that *slow* and *down* have to be told apart, on a clock, rather than
discovered by waiting forever.

---

## Kafka: three lines that decide the shape of the whole consumer

```yaml
kafka:
  bootstrap-servers: localhost:9092
  consumer:
    group-id: inventory-service
  listener:
    auto-startup: false
```

`bootstrap-servers` is unremarkable — the same broker `order-service` already publishes to.

`group-id: inventory-service` is more than a label. Kafka consumer groups are how a broker knows which
consumers are "the same reader" for the purpose of dividing up partitions and tracking read progress.
It is also written verbatim into the `processed_messages` table as the `consumer_name` half of that
table's composite key (FR-029) — so this string is not just configuration, it is data that will end up
in a database row for every message this service ever processes.

**`auto-startup: false` is the single most consequential line in this file, and it does nothing on
its own.** By default, Spring Kafka's `@KafkaListener` methods start consuming the moment the
application context finishes starting up. This line switches that off — the listener exists, is fully
configured, but sits idle until something explicitly starts it.

The reason traces back to one fact about this environment:
`infra/docker-compose.yml` runs Redis with `--save ""` — snapshotting is deliberately disabled, so
**every restart of the Redis container empties it completely.** PostgreSQL survives a restart; Redis
does not, by design (that is exactly why Redis is the cache and PostgreSQL is the durable record — see
`research.md` R4).

Picture what happens if the listener started immediately instead. Redis restarts, loses every current
seat hold, then `inventory-service` restarts, and the listener begins consuming `order.created`
messages before anyone has told Redis "seats A1 and A2 are still held by order X." The very first
booking request after that restart is judged against a Redis that has forgotten every seat currently
in someone's cart — and if that seat happens to be requested again, it looks free, and it gets
double-booked. The service would look completely healthy the entire time. Nothing crashes, nothing
logs an error; it just quietly sells the same seat twice.

`auto-startup: false` is what makes it *possible* to fix this, not the fix itself. The actual fix —
replay every currently-held, unlapsed reservation from PostgreSQL back into Redis, and only then flip
the listener on — is `SeatLockRebuilder`, arriving in T179. This property is the switch that
rebuilder needs to exist; without it, there is no window in which the rebuild can safely happen before
the first message is judged.

---

## The two new configuration blocks: `inventory.*`

```yaml
inventory:
  hold: { ttl-ms: 120000, key-prefix: seat }
  consumer: { max-attempts: 4, backoff-ms: 500 }
  sweeper: { enabled: true, fixed-delay-ms: 30000 }
```

None of this is read by any code yet — `SeatLockScripts`, `KafkaConsumerConfig` and
`LapsedReservationSweeper` all arrive in later tasks and will read these by name
(`@Value("${inventory.hold.ttl-ms}")` or an equivalent `@ConfigurationProperties` class). Writing the
properties now, ahead of the code, means the *number* that governs a behaviour lives in one obvious
place from the start, rather than being invented as a hardcoded literal somewhere inside a class and
migrated out here later, once someone notices a test needs to override it.

`hold.ttl-ms: 120000` is exactly what `FR-008` requires — 120 seconds, not a suggestion. It is not
meant to vary between environments; it is here as a named constant rather than a magic number,
consulted from exactly one place.

`sweeper.enabled` exists specifically so `LapsedRebookingIT` (T149) can run with it turned off and
prove that rebooking a lapsed seat still works. If that test could not disable the sweeper, a passing
result would leave open the question of whether correctness actually depends on the sweeper having
run recently — which `research.md` (R6) is explicit must never be true.

---

## The outbox block: copied on purpose

```yaml
outbox:
  relay:
    poll-interval-ms: 500
    batch-size: 100
    max-attempts: 5
```

Identical property names and values to `order-service`'s. This is deliberate, not laziness: this
service's outbox (arriving in T130–T133) is the *same mechanism*, ported rather than shared as its own
library (`research.md` R8 explains why duplication was chosen over an abstraction here). Giving the
identical mechanism a different-looking configuration surface would invent a distinction that carries
no actual difference in meaning.

---

## What was deliberately left out of this file

`spring.kafka.consumer`'s deserializer, the `DefaultErrorHandler`, its backoff policy, and the
dead-letter destination resolver are **not** here — they are set in Java code, in
`KafkaConsumerConfig` (T177). The same principle governs this choice as governs why `order-service`
keeps its producer's `acks`/idempotence settings in code rather than in this file: a value that
determines whether a message is retried, dead-lettered, or silently dropped is a *correctness*
requirement, not an environment knob. A file whose entire purpose is "the thing you edit when moving
between environments" is the wrong place to trust with a decision about data safety.

---

## Verifying it

There is nothing to run yet that exercises this file — no entity, no listener, no scheduled job reads
any of it. What *can* be verified at this point is that the file is syntactically sound YAML that
Spring Boot can load without complaint, which T118 does as part of confirming the module still joins
the build.
