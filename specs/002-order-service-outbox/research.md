# Phase 0 Research: Order Acceptance & the Transactional Outbox

**Feature**: `002-order-service-outbox` | **Date**: 2026-08-23

Every decision below was forced by a requirement in [spec.md](./spec.md) or by a constraint the
previous step froze. Where a choice has a real cost, the rejected alternative is named.

---

## R1 — Atomicity: what "one transaction" actually buys

**Decision.** The order row and its outbox row are written by a single `@Transactional` service
method against one PostgreSQL database. No message is sent inside that method.

**Rationale.** The whole feature exists because a database and a message broker cannot be committed
together. Reduce the problem to one resource and the atomicity is free — PostgreSQL already gives it.
What remains is not "did both happen" but "the send might happen twice", which is a far weaker and
entirely survivable problem, and one the consumers built in step 3 already have to solve anyway.

**Alternatives considered.**

- *XA / two-phase commit across PostgreSQL and Kafka.* Rejected: Kafka's transaction support is not
  an XA resource manager, Spring's `JtaTransactionManager` would need a standalone coordinator, and
  the coordinator itself becomes a new single point of failure with its own recovery log. This is the
  complexity the outbox pattern exists to avoid.
- *Publish inside the transaction and roll back if the send fails.* Rejected: a send that times out
  may still have succeeded. Rolling back then deletes an order the broker has already announced.
- *Publish after commit via `TransactionSynchronization#afterCommit`.* Rejected as the primary
  mechanism: it narrows the window but does not close it. A crash between commit and the callback
  loses the message with no record that it was owed. It is, however, worth keeping as an
  *optimisation* — see R3.

---

## R2 — Claiming outbox rows without breaking per-order ordering

This is the hard part of the step and the exact contract the developer implements.

**The conflict.** FR-012 wants rows claimed exclusively so two relays never send the same row —
`SELECT … FOR UPDATE SKIP LOCKED` is the standard answer. FR-014 wants rows for one order sent in
recording order. Naive `SKIP LOCKED` breaks that: relay A can claim row 1 while relay B claims row 2
of the same order, and B may finish first.

**Decision.** Claim at most **one row per aggregate per poll — the earliest unsent one** — and skip
any aggregate that already has a parked row ahead of the candidate:

```sql
SELECT *
FROM   outbox o
WHERE  o.status = 'PENDING'
  AND  o.id = (SELECT MIN(i.id) FROM outbox i
               WHERE i.aggregate_id = o.aggregate_id AND i.status = 'PENDING')
  AND  NOT EXISTS (SELECT 1 FROM outbox p
                   WHERE p.aggregate_id = o.aggregate_id
                     AND p.status = 'PARKED'
                     AND p.id < o.id)
ORDER BY o.id
LIMIT  :batchSize
FOR UPDATE SKIP LOCKED;
```

**Rationale.** Ordering stops being something the relay has to arrange and becomes something the
query makes impossible to get wrong. A later row for an order is simply not visible to any relay
until the earlier one leaves `PENDING`. That holds no matter how many relays run, because the
guarantee lives in the predicate rather than in coordination between them. The `NOT EXISTS` clause is
what implements FR-030: a parked row is not `PENDING`, so without that clause `MIN(id)` would step
straight over it and publish the next row out of order — the exact bug the parking decision was meant
to prevent.

**Cost.** One order can advance by only one message per poll cycle. With a 500 ms poll and at most
three messages across an order's whole life, seconds apart, this costs nothing measurable.

**Alternatives considered.**

- *Plain `FOR UPDATE SKIP LOCKED` ordered by id.* Rejected: breaks FR-014 under more than one relay,
  and does so intermittently, which is the worst way for an ordering bug to behave.
- *`pg_try_advisory_xact_lock(hashtext(aggregate_id))` per order.* Works and allows several rows per
  order per poll. Rejected as more machinery for no benefit here: it introduces a lock namespace
  shared process-wide, and `hashtext` collisions silently serialise unrelated orders.
- *One partition/queue per order.* Rejected outright — unbounded number of queues.
- *Single global relay lock.* Rejected: correct but serialises every order behind every other, and
  makes the multi-instance requirement (SC-006) meaningless.

---

## R3 — Publishing: synchronous acknowledgement, inside the claim transaction

**Decision.** For each claimed row the relay blocks on the producer acknowledgement, then marks the
row sent. Claim, send, and mark all happen inside the one relay transaction. Producer settings:
`acks=all`, `enable.idempotence=true`, `max.in.flight.requests.per.connection=5`.

**Rationale.** Blocking is what makes "marked sent" mean "the broker has it". Fire-and-forget would
mark rows sent that never arrived, converting the at-least-once guarantee into no guarantee at all.
Keeping the mark inside the claim transaction means a crash mid-batch rolls the marks back and the
rows are simply retried — which is precisely the duplicate the design already accepts. Idempotent
producer settings keep Kafka's own internal retries from reordering or duplicating within a partition.

**Cost.** Throughput per poll is bounded by round-trip latency times batch size. With a batch of 100
and sub-millisecond local broker latency this is far above the 200/sec target of FR-032.

**Optimisation, deliberately deferred.** An `afterCommit` hook could nudge the relay the instant an
order is accepted, cutting the SC-004 two-second budget to milliseconds. It is a pure latency
optimisation on top of a correct poller, never a replacement for it. Not built in this step; noted so
it is recognised as available rather than rediscovered.

---

## R4 — Carrying trace context across the outbox gap

**Decision.** Store the W3C `traceparent` and `tracestate` values on the outbox row as two plain
`varchar` columns, captured at recording time from Micrometer's `Propagator`. At send time the relay
injects them into the Kafka record's headers and runs the send inside the extracted span context.
Format is W3C Trace Context, which is Spring Boot 3.x's default propagation.

**Rationale.** The clarification session settled that one trace must span request → publish →
consumers. The trace context lives on the *row*, never inside the payload, which is what keeps the
frozen contracts free of observability concerns (step 1's FR-024) — a change to how the system is
traced can therefore never force a contract version bump. Two `varchar` columns rather than a `jsonb`
blob because W3C Trace Context is exactly these two fields and naming them documents the format.

**Consequence for the local environment.** Tracing instrumentation is always active and always writes
these columns; only the *exporter* needs Zipkin. Zipkin sits in the `obs` Compose profile, so
verifying SC-012 requires `COMPOSE_PROFILES=core,obs`, while everything else in this step runs under
`core`. Recorded in [quickstart.md](./quickstart.md) so it is not discovered as a mystery empty trace
view.

**Alternatives considered.**

- *B3 propagation.* Rejected: W3C is the Boot 3 default and the interoperable standard; choosing B3
  would mean configuring every later service to match for no gain.
- *Put trace ids in the message body.* Rejected: directly contradicts step 1's FR-024 and would make
  every observability change a contract change.
- *Re-derive the trace from `sagaId`.* Rejected: correlating by business key is a search, not a
  trace — it produces a list of unrelated traces rather than one connected timeline.

---

## R5 — Bounded in-flight work and fast refusal

**Decision.** Bound concurrency at the connection pool and fail fast when it is exhausted: HikariCP
`maximum-pool-size: 20`, `connection-timeout: 250ms`, plus a transaction timeout of 3 seconds and a
matching PostgreSQL `statement_timeout`. A pool timeout surfaces as `CannotAcquireResourceException`
and is mapped to **503 Service Unavailable** with `Retry-After`, counted under its own metric.

**Rationale.** The connection pool is already the real bottleneck for a write path — every accepted
request needs a connection for its transaction. Making the pool the explicit bound means one
mechanism governs admission instead of two that can disagree. A 250 ms wait is long enough to absorb
ordinary jitter and short enough that a refused caller learns quickly, which is what FR-035 asks for:
a slow store degrades into fast refusals rather than a queue of requests all timing out together.

**Alternatives considered.**

- *Resilience4j `Bulkhead`.* The natural home for this, and Resilience4j is in the project stack —
  but it arrives at build step 8. Adding it here means two admission mechanisms whose interaction has
  to be reasoned about. Deferred deliberately: step 8 may replace this configuration, and that is a
  clean substitution rather than an addition.
- *Tomcat `max-connections` / thread limits.* Rejected as the primary control: it bounds sockets, not
  database work, so it refuses the wrong requests — reads would be refused to protect writes.
- *Unbounded, rely on defaults.* Rejected by the clarification session; it is the behaviour that
  turns a burst into a wall of timeouts.

---

## R6 — HTTP surface and how refusals stay distinguishable

**Decision.** `POST /api/orders` → **202 Accepted**, `Location: /api/orders/{id}`, body `{orderId,
status}`. `GET /api/orders/{id}` → **200** or **404**. Validation failure → **400**. Malformed
identifier → **400**. Capacity refusal → **503** with `Retry-After: 1`. All error responses use RFC
7807 `ProblemDetail`, native to Spring Boot 3, each carrying a distinct stable `type` URI.

**Rationale.** FR-036 requires a capacity refusal to be machine-distinguishable from a bad request
and from a fault. Status code alone nearly does it; a stable `type` URI makes it unambiguous and
survives the gateway rewriting things in step 7. 202 rather than 201 is the honest code: the order
exists, the *booking* does not, and 201 Created would tell the buyer they have seats.

**Path prefix.** `/api/orders` from the start, matching the gateway routes of step 7, so routing later
requires no change here.

---

## R7 — Serialization, and why the payload column is written once

**Decision.** The payload is serialized at recording time by an `ObjectMapper` configured in this
service and stored in the outbox row. The relay publishes those stored bytes verbatim, so the Kafka
producer uses `StringSerializer`, not a JSON serializer. Mapper configuration:
`JavaTimeModule`, `WRITE_DATES_AS_TIMESTAMPS=false`, `WRITE_BIGDECIMAL_AS_PLAIN=true`,
`FAIL_ON_UNKNOWN_PROPERTIES=false`.

**Rationale.** FR-010 says what a consumer receives is decided when the row is written, not when it
is sent — otherwise a deployment between recording and sending silently changes a message already
promised. Serializing twice would reopen exactly that gap. `WRITE_BIGDECIMAL_AS_PLAIN` is not
cosmetic: without it a two-decimal amount can serialize as `1E+2`, which the step-1 schema pattern
rejects, and the failure appears in a *consumer* rather than here.

**Column type: `jsonb`.** TRADEOFF — PostgreSQL normalises `jsonb`, reordering object keys and
dropping insignificant whitespace, so the bytes read back are not byte-identical to those written.
`text` would preserve them exactly. `jsonb` is chosen anyway because the *document* is unchanged —
consumers parse JSON, they do not hash bytes — and being able to query and read payloads directly is
worth a great deal when diagnosing a stalled saga. Recorded here because "exact serialized form" in
FR-010 means "decided once", not "byte-preserved", and the distinction should not have to be
rediscovered.

---

## R8 — Schema migrations

**Decision.** Flyway, migrations under `src/main/resources/db/migration`, one concern per file:
`V1__create_orders.sql`, `V2__create_outbox.sql`. Applied automatically on startup. Each service owns
its own schema and its own migration history; no service reads another's tables.

**Rationale.** FR-023 asks that a clean checkout and an existing installation converge without manual
steps. Splitting by concern keeps each file reviewable and makes a later `V3__` additive rather than
an edit to history, which Flyway forbids by checksum anyway.

---

## R9 — Optimistic locking, honestly scoped

**Decision.** `@Version` on the `Order` entity, with the column and its behaviour covered by a test
that provokes a genuine conflict. The retry-once-on-conflict wrapper is **not** built in this step.

**Rationale.** FR-022 requires the losing writer to be *detected*, and `@Version` does that from the
moment it exists. But nothing in step 2 updates an order — the only transition is into `PENDING` at
creation. Building retry logic now would mean writing a code path with no caller and no honest test,
which the constitution's rule against speculative abstraction rules out. The column must exist now,
because adding it later means a migration plus a version-initialisation backfill; the retry belongs
with the first real update, in step 4.

---

## R10 — Testing strategy

**Decision.** Testcontainers for PostgreSQL and Kafka. Unit tests for validation and payload
construction; `*IT` integration tests for everything involving either container. The relay's
integration tests are written now and **fail until the developer implements the stubbed method**.

**Rationale.** The constitution requires that a test fail before the corresponding implementation and
pass after. The stub arrangement produces that naturally rather than by ceremony: the tests are the
specification of the method the developer is being asked to write, and going green is the definition
of done. Concurrency coverage — required explicitly by Constitution II — is carried by three tests:
concurrent acceptance (SC-001), multi-relay exclusive claiming (SC-006), and per-order ordering under
concurrent relaying (SC-007).

---

## R11 — Module scaffolding stays with the developer

**Decision.** The `order-service` module is generated by the developer from start.spring.io using the
exact dependency set and settings given in [quickstart.md](./quickstart.md). Afterwards the parent is
repointed at `ticket-marketplace`, the `common-events` dependency is added, and one `<module>` line
goes into the root pom.

**Rationale.** Constitution Principle V governs first-time provisioning, and fetching a generated
project archive is exactly that. It is also how every module in this project has been created so far,
so the build stays consistent with what the developer already recognises.

**Post-generation edits required** (Initializr cannot produce these): swap the `spring-boot-starter-
parent` parent for `ticket-marketplace`; drop the redundant `<properties><java.version>` and version
tags inherited from the root; add `common-events`; keep `spring-boot-maven-plugin` in *this* module,
since a service is an executable application even though the root deliberately has no such plugin.

---

## R12 — Metrics for the outbox

**Decision.** Micrometer meters registered by the service and scraped from `/actuator/prometheus`:

| Meter | Type | Serves |
|---|---|---|
| `outbox.records.parked` | gauge | FR-031 — parked count |
| `outbox.oldest.pending.age.seconds` | gauge | FR-031 — backlog age |
| `outbox.records.published` | counter | relay throughput, FR-033 |
| `outbox.send.failures` | counter | distinguishes retryable failures from parking |
| `orders.refused.capacity` | counter | FR-036 — overload visible as overload |

**Rationale.** Backlog *age* rather than backlog *depth* is the meter that matters: depth spikes
harmlessly during a burst and says nothing, whereas a rising oldest-pending age means the relay is
losing ground, which is the actual failure. Both gauges read the database on scrape, which is cheap
against the partial index from R8 and honest — a cached value would report health after the relay
has stopped.

---

## R13 — What this step deliberately does not build

Recorded so each omission reads as a decision rather than an oversight.

| Not built | Why | Arrives in |
|---|---|---|
| `processed_events` idempotency guard | order-service consumes nothing yet | Step 3 |
| Retry-once on optimistic lock failure | no order update exists to conflict — R9 | Step 4 |
| Eureka client | no registry exists to register with | Step 7 |
| JWT validation | validated at the gateway, not per service | Step 7 |
| Resilience4j bulkhead/circuit breaker | R5's pool bound covers this step | Step 8 |
| Outbox retention or archival | sent rows are harmless behind a partial index | Operational, later |
| `order-service` entry in docker-compose | the roadmap defers "does `make up` build jars first?" to step 7, when the first project-built image appears | Step 7 |
