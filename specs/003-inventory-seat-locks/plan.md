# Implementation Plan: Seat Holds & the Inventory Authority

**Branch**: `003-inventory-seat-locks` | **Date**: 2026-08-27 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/003-inventory-seat-locks/spec.md`

## Summary

Build `inventory-service`: the first consumer in the system and the authority on whether a seat can be
had. It reads `order.created`, decides all-or-nothing whether every requested seat is free, and answers
with `seats.reserved` or `seats.rejected`.

The technical shape follows from two observations.

**The decision must be one indivisible act.** Checking each seat and then taking it leaves a window in
which every other contender does the same thing and they all conclude the seats were free. A Lua
script evaluated by Redis closes that window by construction, because Redis runs a script to
completion before serving any other command (R1). This is the piece the developer writes by hand — it
is four lines long and it is the difference between a marketplace that double-books and one that does
not (R11).

**Speed and truth are different jobs.** Redis arbitrates contention because it is fast and its holds
expire on their own; PostgreSQL is the authority because it survives a restart, and Redis in this
environment explicitly does not (`--save ""`). So the durable reservation is the source of truth and
Redis is a cache of it, rebuilt from it before the listener ever starts (R4). Underneath both sits a
partial unique index that refuses to record two live claims on one seat, which is the guarantee that
holds even if the Lua script is wrong (R5).

Everything else keeps that arrangement honest: an idempotency guard that runs *before* contention so a
redelivery never refuses itself (R7), a second transactional outbox so a decided outcome cannot be lost
between commit and publish (R8), and failure routing that never announces a seat refusal for a problem
that was never about the seats (R9).

Two Lua script bodies ship as documented stubs for the developer to implement, with their tests written
and failing. That is a deliberate scope decision carried over from CLAUDE.md's `TODO(me)` markers, not
an omission.

## Technical Context

**Language/Version**: Java 21 (verified: OpenJDK 21.0.11)

**Primary Dependencies**: Spring Boot 3.3.13 (Web, Data JPA, Data Redis, Validation, Actuator), Spring
for Apache Kafka, Flyway, PostgreSQL driver, Lettuce (Redis client, via Spring Data Redis), Micrometer
Tracing (Brave bridge) + Zipkin reporter, Micrometer Prometheus registry, `common-events` from step 1

**Storage**: PostgreSQL 16, schema `inventory` — `shows`, `show_seats`, `reservations`,
`reservation_seats`, `processed_messages`, `outbox`. Redis 7 for seat holds. Schema created by Flyway
on startup (R12).

**Testing**: JUnit 5, AssertJ, Testcontainers (PostgreSQL + Redis + Kafka), Awaitility. Unit tests via
Surefire, `*IT` via Failsafe — both already configured at the build root.

**Target Platform**: Linux developer workstation, Docker Compose `core` profile (`core,obs` for the
tracing check)

**Project Type**: Spring Boot service, one module in the existing multi-module Maven build. First
module in the project that consumes messages.

**Performance Goals**: absorb the 200 requests/sec order-service sustains (SC-004); 1,000-request burst
against a 10-seat pool decided without capacity failures (SC-001); a refusal costing no more than an
acceptance (SC-020)

**Constraints**: decision p95 under 150 ms (SC-004); hold lifetime exactly 120 s (FR-008); expired
holds free within 5 s of lapsing (SC-005); outcome on the channel within 10 s of restart (SC-007);
rebuild complete before the first message is judged (FR-015)

**Scale/Scope**: one service module, six tables, two Redis scripts, one consumer, one message type
consumed, two produced, one scheduled relay, one scheduled sweeper. No HTTP surface beyond actuator.

**Port**: 8082. 8080 stays free for the gateway in step 7; 8081 is order-service.

### Environment findings (checked, not assumed)

| Prerequisite | Status |
|---|---|
| Java 21 | ✅ OpenJDK 21.0.11 |
| Maven | ✅ wrapper resolves 3.9.16 |
| Docker engine / Compose | ✅ 29.7.2 / v2.39.1 |
| PostgreSQL container | ✅ `core` profile, 5432, `max_connections=50` — budget noted in R12 |
| Redis container | ✅ `core` profile, 6379, `noeviction`, **`--save ""`** — no persistence, which is what makes R4's rebuild mandatory rather than defensive |
| Kafka container | ✅ `core` profile, 9092, 14 channels including `order.created.DLT` |
| Zipkin container | ⚠️ `obs` profile — needed only for the trace check (SC-015) |
| `common-events` | ✅ built, published `OrderCreated` / `SeatsReserved` / `SeatsRejected` frozen |
| `order-service` | ✅ complete, publishing `order.created` — this step's message source |
| `inventory-service` module | ❌ not scaffolded — developer action, quickstart §0 (Principle V) |

Two items need action: the developer generates the module from start.spring.io, and `COMPOSE_PROFILES`
moves to `core,obs` for the tracing criterion. Both are in [quickstart.md](./quickstart.md); per
Constitution Principle V this plan performs neither.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Gate | Pre-Phase 0 | Post-Phase 1 |
|---|---|---|---|
| I. Code Quality | Single responsibility; documented at definition; no speculative abstraction; reviewed before merge | ⚠️ Conditional | ⚠️ Conditional |
| II. Testing Standards | Unit tests for logic; producer/consumer contract tests; concurrency-focused tests; fail before, pass after | ✅ Pass | ✅ Pass |
| III. UX Consistency | Consistent surfaces; defined success/error states for asynchronous updates | ✅ Pass | ✅ Pass |
| IV. Performance | Explicit latency budget; validated by load testing; no unbounded blocking in the critical path | ⚠️ Conditional | ⚠️ Conditional |
| V. Human-Gated Tooling | No autonomous install, credentialing, or provisioning | ✅ Pass | ✅ Pass |

**I — Code Quality.** Each class has one job: a listener that maps a message to a command, one
transactional service method that decides and records, a script executor that owns the Redis calls, a
relay that drains, a sweeper that tidies. The outbox is copied from order-service rather than extracted
into a shared module — the constitution requires an abstraction to be justified by demonstrated need,
and a second instance makes the need *visible* rather than demonstrated (R8). That is recorded here so
it is a decision, not drift. Public surfaces are documented where they are defined: the script contract
in [`contracts/seat-lock-scripts.md`](./contracts/seat-lock-scripts.md), the consumer's obligations in
[`contracts/inventory-consumer.md`](./contracts/inventory-consumer.md). *Conditional* only on the
review clause — see Complexity Tracking.

**II — Testing Standards.** This is the principle the spec called out as biting hardest, and the design
answers it in two places rather than one. The constitution names ticket reservation specifically as
requiring tests that exercise concurrent execution — so the exact-count assertions run hundreds of
genuinely simultaneous threads against real Redis and real PostgreSQL, because the message channel caps
in-process concurrency at its three partitions and would let a broken hold pass by luck (R10). The
producer/consumer contract rule arms properly for the first time: order-service produces
`order.created` and this service consumes it, so `SagaEndToEndIT` exercises a real pair over a real
broker rather than a handler in isolation. "Fail before, pass after" is structural — the Lua bodies are
empty and their tests fail until the developer writes them (R11).

**III — UX Consistency.** No UI exists, so WCAG does not apply. The applicable clause — that an
asynchronous update always has a defined success and failure state — is honoured at the message layer:
every consumed request produces exactly one outcome (FR-022), refusals carry a stated cause from a
fixed set rather than prose (FR-023), and a request that could not be judged produces *no* outcome
rather than a misleading one (FR-047). A buyer is never told their seats were taken because a database
was briefly slow.

**IV — Performance.** The critical path has an explicit budget (SC-004) and every blocking operation is
bounded: the Redis call carries a command timeout, the transaction a 3 s limit with a matching
`statement_timeout`, and the connection pool is capped at 12 (R12). The Lua script is O(seats) with no
loop over the keyspace. The sweeper is deliberately off the path a buyer waits behind (R6).
*Conditional* on the load-validation clause — see Complexity Tracking.

**V — Human-Gated Tooling.** Scaffolding the module means fetching a generated archive from
start.spring.io, which is first-time provisioning and therefore the developer's to run (quickstart §0).
No credentials are generated; the PostgreSQL and Redis endpoints are the committed local-only values
from step 1. Schema isolation was chosen partly because it needs no provisioning at all (R12).

**Result**: two documented deviations, both carried forward from step 2 with unchanged justification.
No unjustified violations.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| **Principle I** — work lands on `main` by direct commit, without a pull request or a second reviewer | Single-developer portfolio and interview project; no second reviewer exists, and the established workflow is one commit per task so the history reads as a build log. The review clause presumes a team. | Opening pull requests to oneself adds ceremony without adding review. Recorded rather than ignored, so the clause is understood as inapplicable rather than forgotten — it re-arms the moment a second contributor appears. |
| **Principle IV** — authoritative load validation of the reservation path happens in build step 9, not here | The k6 harness is itself a step-9 deliverable, and it drives the saga through the gateway, which does not exist until step 7. | Building a throwaway harness now was rejected as duplicated work. Instead SC-001's 1,000-way contention is asserted directly as an integration test (R10) — which is a *stronger* correctness check than k6 gives, and weaker only on sustained-rate measurement, which step 9 supersedes. |

## Project Structure

### Documentation (this feature)

```text
specs/003-inventory-seat-locks/
├── plan.md              # This file
├── research.md          # Phase 0 output — R1..R14
├── data-model.md        # Phase 1 output — tables, state machines, contract mapping
├── quickstart.md        # Phase 1 output — scaffolding steps + validation scenarios
├── contracts/           # Phase 1 output
│   ├── README.md
│   ├── seat-lock-scripts.md     # The two stubbed Lua scripts, as guarantees and tests
│   └── inventory-consumer.md    # What consuming order.created obliges this service to do
├── checklists/
│   └── requirements.md  # From /speckit-specify + /speckit-clarify
└── tasks.md             # Created later by /speckit-tasks
```

### Source Code (repository root)

```text
pom.xml                                   # + <module>inventory-service</module>

inventory-service/
├── pom.xml                               # parent → ticket-marketplace; + common-events, data-redis, tracing, prometheus
├── Dockerfile                            # multi-stage, mirrors order-service
└── src/
    ├── main/
    │   ├── java/com/marketplace/inventory/
    │   │   ├── InventoryServiceApplication.java
    │   │   ├── consume/
    │   │   │   ├── OrderCreatedListener.java     # @KafkaListener → ReservationService
    │   │   │   ├── ProcessedMessage.java         # @Entity, composite id (R7)
    │   │   │   ├── ProcessedMessageId.java       # @Embeddable
    │   │   │   ├── ProcessedMessageRepository.java
    │   │   │   ├── IdempotencyGuard.java         # insert-or-skip; body stubbed (CLAUDE.md TODO(me))
    │   │   │   └── UnknownSchemaVersionException.java   # non-retryable → DLT
    │   │   ├── seats/
    │   │   │   ├── SeatLockScripts.java          # DefaultRedisScript<Long> beans + key builder
    │   │   │   ├── SeatLockStore.java            # the calling method; wraps both scripts
    │   │   │   └── SeatKey.java                  # seat:{showId}:{seatId} — showId, never messageId (R3)
    │   │   ├── domain/
    │   │   │   ├── Reservation.java              # @Entity, @Version
    │   │   │   ├── ReservationSeat.java          # @Entity, carries released_at (R5)
    │   │   │   ├── ReservationStatus.java        # HELD | EXPIRED | COMMITTED | RELEASED
    │   │   │   ├── ReservationRepository.java
    │   │   │   ├── Show.java / ShowSeat.java     # the seating plan
    │   │   │   └── SeatingPlanRepository.java
    │   │   ├── service/
    │   │   │   ├── ReservationService.java       # THE @Transactional decide-and-record method
    │   │   │   └── ReservationOutcome.java       # sealed: Reserved | Rejected(cause)
    │   │   ├── startup/
    │   │   │   └── SeatLockRebuilder.java        # ApplicationRunner: rebuild, THEN start listeners (R4)
    │   │   ├── sweeper/
    │   │   │   └── LapsedReservationSweeper.java # @Scheduled tidy-up, never load-bearing (R6)
    │   │   ├── outbox/                           # ported from order-service (R8)
    │   │   │   ├── OutboxRecord.java / OutboxStatus.java
    │   │   │   ├── OutboxRepository.java         # the claim query
    │   │   │   ├── OutboxWriter.java             # builds SeatsReserved / SeatsRejected
    │   │   │   ├── OutboxRelay.java              # @Scheduled @Transactional pollAndPublish()
    │   │   │   └── OutboxMetrics.java
    │   │   └── config/
    │   │       ├── JacksonConfig.java
    │   │       ├── KafkaProducerConfig.java
    │   │       ├── KafkaConsumerConfig.java      # DefaultErrorHandler + DLT resolver (R9)
    │   │       ├── RedisConfig.java
    │   │       └── TracingConfig.java
    │   └── resources/
    │       ├── application.yml
    │       ├── scripts/
    │       │   ├── lock_seats.lua                # ← STUB: header comment only (R11)
    │       │   └── release_seats.lua             # ← STUB: header comment only (R11)
    │       └── db/migration/
    │           ├── V1__create_seating_plan.sql   # shows, show_seats + seed
    │           ├── V2__create_reservations.sql   # reservations, reservation_seats, the live index
    │           ├── V3__create_processed_messages.sql
    │           └── V4__create_outbox.sql
    └── test/java/com/marketplace/inventory/
        ├── InventoryIT.java                      # base: Postgres + Redis, no Kafka
        ├── InventoryKafkaIT.java                 # base: adds Kafka + independent consumer
        ├── SeatKeyTest.java                      # unit — key uses showId, not messageId (R3)
        ├── OutcomeMappingTest.java               # unit — mapping to the frozen contracts is total
        ├── SeatLockScriptIT.java                 # the two scripts, all-or-nothing + ownership
        ├── ReservationContentionIT.java          # SC-001 — 1,000 threads, 10 seats, exactly 10
        ├── ReservationPartialOverlapIT.java      # SC-002 — no partial holds
        ├── ReservationDisjointIT.java            # SC-003 — 500 disjoint, all granted
        ├── ReservationRejectionIT.java           # SC-008 — each cause by its own condition
        ├── ReservationVersionIT.java             # SC-011 — retry once, then surface
        ├── LiveSeatConstraintIT.java             # SC-017 — the index holds with Redis bypassed
        ├── LapsedRebookingIT.java                # SC-016 — sweeper disabled, rebooking still works
        ├── IdempotencyIT.java                    # SC-006 — ten deliveries, one effect
        ├── SeatLockRebuildIT.java                # SC-013/014 — flush Redis, restart, holds restored
        ├── UndecidableRequestIT.java             # SC-018/019 — no false refusal; DLT at the limit
        └── SagaEndToEndIT.java                   # SC-009/015 — real order.created → real outcome
```

**Structure Decision.** Packages are split by concern rather than by layer, matching order-service.
`seats/` holds the contention mechanism and nothing else, so the part of this service worth
understanding as a unit reads in one directory — the same reason `outbox/` was grouped that way in
step 2. `consume/` holds everything about *receiving* a message, including the idempotency guard, which
belongs with delivery rather than with the domain.

`ReservationService` is deliberately the only place the decision is made and recorded. Making one
method the sole writer of the reservation, its seats, the processed-message row and the outbox row is
what makes FR-025's atomicity claim reviewable: there is exactly one place to look.

`startup/` is its own package for one class because the ordering it enforces — rebuild before consume —
is a correctness requirement that is invisible at every call site (FR-015). A service that gets it wrong
looks perfectly healthy and double-books.

## Phase status

| Phase | Output | Status |
|---|---|---|
| 0 — Research | [research.md](./research.md) | ✅ Complete, no unresolved unknowns |
| 1 — Design & Contracts | [data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md) | ✅ Complete |
| 2 — Tasks | `tasks.md` | ⏳ Created by `/speckit-tasks` |
