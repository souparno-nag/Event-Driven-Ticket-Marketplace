# Implementation Plan: Order Acceptance & the Transactional Outbox

**Branch**: `002-order-service-outbox` | **Date**: 2026-08-23 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/002-order-service-outbox/spec.md`

## Summary

Build `order-service`: the front door of the marketplace and the origin of every saga. It accepts a
booking request, writes the order and the message announcing it in one PostgreSQL transaction, and
runs a background relay that drains those messages onto `order.created`.

The technical shape follows from one observation — a database and a broker cannot be committed
together, so the design reduces the problem to a single resource and accepts the weaker consequence.
Both writes go to one database, making them genuinely atomic; the relay then publishes at least once,
and duplicate suppression becomes the consumer's job in step 3. Everything else in this plan exists
to keep that arrangement honest under concurrency: a claim query that makes out-of-order publication
unrepresentable rather than merely unlikely (R2), a synchronous acknowledgement so "marked sent" means
"the broker has it" (R3), and trace context carried on the row so the outbox gap does not sever the
trace (R4).

One method — the relay's `pollAndPublish` body — ships as a documented stub for the developer to
implement, with its tests written and failing. That is a deliberate scope decision recorded in the
spec's clarification session, not an omission.

## Technical Context

**Language/Version**: Java 21 (verified: OpenJDK 21.0.11)

**Primary Dependencies**: Spring Boot 3.3.13 (Web, Data JPA, Validation, Actuator), Spring for Apache
Kafka, Flyway, PostgreSQL driver, Micrometer Tracing (Brave bridge) + Zipkin reporter, Micrometer
Prometheus registry, `common-events` from step 1

**Storage**: PostgreSQL 16 — two tables, `orders` (+ `order_seats`) and `outbox`, owned solely by this
service. Schema created by Flyway on startup.

**Testing**: JUnit 5, AssertJ, Testcontainers (PostgreSQL + Kafka), Awaitility for the relay's
eventual-consistency assertions. Unit tests via Surefire, `*IT` via Failsafe — both already configured
at the build root.

**Target Platform**: Linux developer workstation, Docker Compose `core` profile (`core,obs` for the
tracing check)

**Project Type**: Spring Boot web service, one module in the existing multi-module Maven build

**Performance Goals**: 200 accepted requests/sec sustained (FR-032); absorb a 1,000-request burst
(FR-032); relay drains at least as fast as acceptance (FR-033); 500/sec recorded as a stretch target
that no decision here may preclude (FR-034)

**Constraints**: acceptance p95 under 300 ms (SC-003); message on the channel within 2 s of commit
(SC-004); outstanding messages sent within 10 s of restart (SC-005); capacity refusal returned in
under 100 ms (SC-016)

**Scale/Scope**: one service module, two tables, two HTTP endpoints, one message type published, one
scheduled relay. No consumers — this service subscribes to nothing until step 4.

**Port**: 8081. 8080 is left free for the gateway in step 7.

### Environment findings (checked, not assumed)

| Prerequisite | Status |
|---|---|
| Java 21 | ✅ OpenJDK 21.0.11 |
| Maven | ✅ wrapper resolves 3.9.16 |
| Docker engine | ✅ 29.7.2, daemon active, native Ubuntu engine |
| Docker Compose | ✅ v2.39.1 |
| PostgreSQL container | ✅ defined, `core` profile, port 5432, `marketplace/marketplace` |
| Kafka container | ✅ defined, `core` profile, port 9092, 14 channels provisioned by `kafka-init` |
| Zipkin container | ⚠️ defined but in the **`obs`** profile — not started by the default `core` |
| `order-service` module | ❌ not scaffolded — developer action, quickstart §0 (R11) |

Only two items need action: the developer generates the module from start.spring.io, and
`COMPOSE_PROFILES` moves to `core,obs` for the tracing criterion. Both are in
[quickstart.md](./quickstart.md); per Constitution Principle V this plan performs neither.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Gate | Pre-Phase 0 | Post-Phase 1 |
|---|---|---|---|
| I. Code Quality | Single responsibility; documented at definition; no speculative abstraction; reviewed before merge | ⚠️ Conditional | ⚠️ Conditional |
| II. Testing Standards | Unit tests for logic; producer/consumer contract tests; concurrency-focused tests; tests fail before, pass after | ✅ Pass | ✅ Pass |
| III. UX Consistency | Consistent surfaces; defined success/error states for asynchronous updates | ✅ Pass | ✅ Pass |
| IV. Performance | Explicit latency budget; validated by load testing; no unbounded blocking in the critical path | ⚠️ Conditional | ⚠️ Conditional |
| V. Human-Gated Tooling | No autonomous install, credentialing, or provisioning | ✅ Pass | ✅ Pass |

**I — Code Quality.** Each class has one job: a controller that maps HTTP to a command, one
transactional service method that writes both rows, a repository holding the claim query, a relay that
drains. No base classes, no generic outbox framework, no repository abstraction over Spring Data — the
constitution prefers duplication to speculative abstraction and there is nothing here to abstract over
yet. The public surface is documented where it is defined: the HTTP contract in
[`contracts/orders-api.yaml`](./contracts/orders-api.yaml), the relay's contract in
[`contracts/outbox-relay.md`](./contracts/outbox-relay.md). *Conditional* only on the review clause —
see Complexity Tracking.

**II — Testing Standards.** Concurrency is where this feature actually fails, and three integration
tests target exactly that: concurrent acceptance (SC-001), two relays claiming against one store
(SC-006), and per-order ordering under concurrent relaying (SC-007). The producer/consumer contract
rule arms here for the first time — order-service is the project's first real producer. Its counterpart
does not exist until step 3, so the pairing is satisfied by publishing and reading back with an
independent Kafka consumer that deserializes using the step-1 contracts (SC-008), which tests the wire
format rather than a shared in-memory object. The "fail before, pass after" rule is satisfied
structurally rather than ceremonially: the relay's twelve guarantees are written as failing tests and
the developer's implementation is what turns them green.

**III — UX Consistency.** No UI exists, so WCAG does not apply. The parts that do apply are honoured:
every error response is RFC 7807 with a stable `type` URI, so a capacity refusal, a validation failure
and a fault are consistently shaped and machine-distinguishable (FR-036); and 202 rather than 201
means the API never tells a buyer they have seats when the saga has not run — which is the
constitution's rule that a user is never left unsure what happened to their action, expressed at the
API layer where this step's surface lives.

**IV — Performance.** The critical path has an explicit budget (SC-003, SC-014, SC-015) and the plan
bounds every blocking operation: connection acquisition at 250 ms, transactions at 3 s, with a matching
PostgreSQL `statement_timeout` (R5). No unbounded call sits in the accept path — the broker is
deliberately not touched there at all, which is the outbox's other benefit. *Conditional* on the load
validation clause — see Complexity Tracking.

**V — Human-Gated Tooling.** Scaffolding the module means fetching a generated archive from
start.spring.io, which is first-time provisioning and therefore the developer's to run (R11,
quickstart §0). The `apache2-utils` package suggested for the interim throughput check is likewise
offered as a command to run, not executed. No credentials are generated; the PostgreSQL credentials
are the committed local-only pair from step 1.

**Result**: two documented deviations, both recorded below with justification. No unjustified
violations.

## Complexity Tracking

| Violation | Why Needed | Simpler Alternative Rejected Because |
|---|---|---|
| **Principle I** — work lands on `main` by direct commit, without a pull request or a second reviewer | This is a single-developer portfolio and interview project; no second reviewer exists, and the established workflow is one commit per task so the history reads as a build log. The constitution's review clause presumes a team. | Opening pull requests to oneself would add ceremony without adding review. Recorded rather than silently ignored so that the clause is understood as inapplicable here, not forgotten — if a second contributor ever appears, it re-arms immediately. |
| **Principle IV** — the authoritative load validation of the checkout path happens in build step 9, not in this step | The k6 harness is itself a deliverable of step 9, and the load test's real subject is seat locking, which does not exist until step 3. Running it now would measure only this service against a saga that cannot complete. | Building a throwaway load harness now was rejected as duplicated work. Instead an interim check using `ab` is documented (quickstart S8) so the budget is exercised rather than merely asserted, and step 9 supersedes it. Nothing is deployed to users in the interim, so "before it ships" is not yet breached. |

## Project Structure

### Documentation (this feature)

```text
specs/002-order-service-outbox/
├── plan.md              # This file
├── research.md          # Phase 0 output — R1..R13
├── data-model.md        # Phase 1 output — tables, state machines, contract mapping
├── quickstart.md        # Phase 1 output — scaffolding steps + eight validation scenarios
├── contracts/           # Phase 1 output
│   ├── README.md
│   ├── orders-api.yaml      # OpenAPI 3.1: the two endpoints and their RFC 7807 shapes
│   └── outbox-relay.md      # The stubbed method's contract and its twelve guarantees
├── checklists/
│   └── requirements.md  # From /speckit-specify + /speckit-clarify
└── tasks.md             # Created later by /speckit-tasks
```

### Source Code (repository root)

```text
pom.xml                                   # + <module>order-service</module>

order-service/
├── pom.xml                               # parent → ticket-marketplace; + common-events, tracing, prometheus
└── src/
    ├── main/
    │   ├── java/com/marketplace/orders/
    │   │   ├── OrderServiceApplication.java
    │   │   ├── api/
    │   │   │   ├── OrderController.java          # POST + GET, 202/200/400/404/503
    │   │   │   ├── CreateOrderRequest.java       # record + Bean Validation
    │   │   │   ├── CreateOrderResponse.java      # record
    │   │   │   ├── OrderView.java                # record
    │   │   │   └── ApiExceptionHandler.java      # RFC 7807, incl. capacity → 503
    │   │   ├── domain/
    │   │   │   ├── Order.java                    # @Entity, @Version, @ElementCollection seats
    │   │   │   ├── OrderStatus.java              # PENDING | CONFIRMED | CANCELLED
    │   │   │   └── OrderRepository.java
    │   │   ├── outbox/
    │   │   │   ├── OutboxRecord.java             # @Entity
    │   │   │   ├── OutboxStatus.java             # PENDING | PUBLISHED | PARKED
    │   │   │   ├── OutboxRepository.java         # the claim query (R2) — native, @Lock-free
    │   │   │   ├── OutboxWriter.java             # serialize + capture trace context + insert
    │   │   │   ├── OutboxRelay.java              # @Scheduled @Transactional pollAndPublish()  ← STUB
    │   │   │   └── OutboxMetrics.java            # the five meters of R12
    │   │   ├── service/
    │   │   │   └── OrderAcceptanceService.java   # THE @Transactional method: order + outbox
    │   │   └── config/
    │   │       ├── JacksonConfig.java            # BigDecimal plain, JavaTimeModule (R7)
    │   │       └── KafkaProducerConfig.java      # acks=all, idempotent, StringSerializer
    │   └── resources/
    │       ├── application.yml                   # datasource, Hikari bounds, relay props, actuator
    │       └── db/migration/
    │           ├── V1__create_orders.sql
    │           └── V2__create_outbox.sql
    └── test/java/com/marketplace/orders/
        ├── CreateOrderRequestValidationTest.java     # unit
        ├── OrderPayloadMappingTest.java              # unit — contract mapping is total
        ├── OrderAcceptanceIT.java                    # atomicity, rollback, concurrent accept
        ├── OrderApiIT.java                           # 202/200/400/404 and the read-back
        ├── OrderCapacityIT.java                      # 503 under saturation, distinct from 400
        ├── OrderVersionIT.java                       # @Version detects a losing writer
        ├── OutboxRelayIT.java                        # guarantees 1-8
        ├── OutboxTracingIT.java                      # guarantees 9-10
        ├── OutboxConcurrencyIT.java                  # guarantee 11 — two relays
        ├── OutboxOrderingIT.java                     # guarantee 12 — per-order ordering
        └── OutboxRestartRecoveryIT.java              # SC-005
```

**Structure Decision.** Packages are split by concern rather than by layer — `api`, `domain`,
`outbox`, `service`, `config` — because the outbox is the part of this service worth understanding as
a unit, and a layered `controller/service/repository` split would scatter its four classes across
three packages. `outbox/` holds everything the pattern needs and nothing else, so the whole mechanism
reads in one directory.

`OrderAcceptanceService` is deliberately the only place both tables are written. Making that one
method the sole writer is what makes the atomicity claim reviewable: there is exactly one place to
look to verify FR-007 holds.

## Phase status

| Phase | Output | Status |
|---|---|---|
| 0 — Research | [research.md](./research.md) | ✅ Complete, no unresolved unknowns |
| 1 — Design & Contracts | [data-model.md](./data-model.md), [contracts/](./contracts/), [quickstart.md](./quickstart.md) | ✅ Complete |
| 2 — Tasks | `tasks.md` | ⏳ Created by `/speckit-tasks` |
