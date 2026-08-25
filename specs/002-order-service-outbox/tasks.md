---

description: "Task list for Order Acceptance & the Transactional Outbox"
---

# Tasks: Order Acceptance & the Transactional Outbox

**Input**: Design documents from `/specs/002-order-service-outbox/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: Test tasks ARE included. Constitution Principle II requires them, and this feature's
correctness is almost entirely concurrency behaviour that only an integration test can observe.

**Organization**: Grouped by user story so each is independently implementable and testable.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on incomplete work)
- **[Story]**: US1, US2, US3 — maps to the user stories in spec.md
- **[human]**: The developer performs this, not the assistant (Constitution Principle V, or a
  deliberate learning exercise)
- Every task names an exact file path

## Numbering

Task ids continue from step 1, which ended at T056. Numbering is global across build steps so that
`docs/tasks/T0NN-*.md` filenames never collide and the commit history reads as one continuous build
log.

## Path Conventions

Multi-module Maven project, build root at the repository root. This step adds one module,
`order-service/`, with sources under `order-service/src/main/java/com/marketplace/orders/`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Bring the module into the build so anything can compile.

- [X] T057 **[human]** Generate `order-service` at [start.spring.io](https://start.spring.io) using the exact settings in `specs/002-order-service-outbox/quickstart.md` §0 — Maven, Java 21, Spring Boot 3.3.x, group `com.marketplace`, artifact `order-service`, package `com.marketplace.orders`, dependencies Web / Data JPA / PostgreSQL Driver / Kafka / Flyway / Actuator / Validation. Unzip into the repository root and delete the generated `mvnw`, `mvnw.cmd`, `.mvn/`, and `.gitignore` (R11, Constitution Principle V)
- [X] T058 Adapt `order-service/pom.xml`: replace the `spring-boot-starter-parent` parent with `com.marketplace:ticket-marketplace:0.0.1-SNAPSHOT`, delete the now-inherited `<properties>` and version tags, add `common-events`, `micrometer-tracing-bridge-brave`, `zipkin-reporter-brave`, `micrometer-registry-prometheus`, and test-scope `testcontainers-postgresql`, `testcontainers-kafka`, `testcontainers-junit-jupiter`, `awaitility`. Keep `spring-boot-maven-plugin` in this module — a service is an executable application even though the root deliberately has none (R11)
- [X] T059 Add `<module>order-service</module>` to the root `pom.xml` module list, immediately after `common-events` (step 1's FR-022 — registration alone, no restructuring)
- [X] T060 Create `order-service/src/main/resources/application.yml`: `server.port: 8081`; datasource pointing at `localhost:5432/marketplace`; HikariCP `maximum-pool-size: 20` and `connection-timeout: 250ms`; `spring.transaction.default-timeout: 3s`; Flyway enabled; `spring.jpa.hibernate.ddl-auto: validate`; Kafka bootstrap `localhost:9092`; `outbox.relay.*` properties; actuator exposing `health,info,prometheus`; tracing sampling probability `1.0` and the Zipkin endpoint. Add a WHY comment on `ddl-auto: validate` recording that Flyway owns the schema and Hibernate must never alter it (R5, R8)
- [X] T061 Verify the module joins the build: `./mvnw -q -pl order-service -am verify` succeeds on the generated skeleton

**Checkpoint**: `order-service` compiles as part of the root build. No behaviour yet.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Schema, entities, and serialization that every user story needs.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

- [X] T062 [P] Create `order-service/src/main/resources/db/migration/V1__create_orders.sql` — `orders` and `order_seats` exactly as specified in data-model.md, including the `orders_status_known` and `orders_amount_non_negative` CHECK constraints and the composite primary key on `order_seats`. Add a WHY comment recording that the composite key makes duplicate seats impossible in the database rather than only in validation (FR-004, FR-005, FR-022)
- [X] T063 [P] Create `order-service/src/main/resources/db/migration/V2__create_outbox.sql` — the `outbox` table with `status`, `attempts`, `last_error`, `traceparent`, `tracestate`, both CHECK constraints, and the two partial indexes `idx_outbox_pending` and `idx_outbox_parked`. Add a WHY comment on `outbox_published_consistent` explaining that it stops `status` and `published_at` drifting apart, which is the failure mode of redundant state (FR-008, FR-025, FR-028)
- [X] T064 [P] Create `order-service/src/main/java/com/marketplace/orders/domain/OrderStatus.java` — enum `PENDING`, `CONFIRMED`, `CANCELLED`, with a comment noting only `PENDING` is reachable until step 4 and why all three are declared now (FR-004)
- [X] T065 [P] Create `order-service/src/main/java/com/marketplace/orders/outbox/OutboxStatus.java` — enum `PENDING`, `PUBLISHED`, `PARKED`, with a comment recording why the brief's `published_at IS NULL` alone could not express parking (FR-029)
- [X] T066 Create `order-service/src/main/java/com/marketplace/orders/domain/Order.java` — JPA entity with `@Version`, `@ElementCollection` seat labels mapped to `order_seats`, `BigDecimal` amount, and `@PrePersist`/`@PreUpdate` timestamps. A TRADEOFF comment recording that `@ElementCollection` costs one join and buys a database-level distinctness invariant (FR-004, FR-022)
- [X] T067 Create `order-service/src/main/java/com/marketplace/orders/outbox/OutboxRecord.java` — JPA entity over `outbox` with `@GeneratedValue(strategy = IDENTITY)`. Include the WHY comment from data-model.md recording that identity order is not commit order, and why this system is not exposed to it (FR-008)
- [X] T068 [P] Create `order-service/src/main/java/com/marketplace/orders/domain/OrderRepository.java` — Spring Data `JpaRepository<Order, UUID>`, no custom queries yet
- [X] T069 [P] Create `order-service/src/main/java/com/marketplace/orders/outbox/OutboxRepository.java` — Spring Data `JpaRepository<OutboxRecord, Long>`. The claim query is added in US2, not here
- [X] T070 [P] Create `order-service/src/main/java/com/marketplace/orders/config/JacksonConfig.java` — an `ObjectMapper` bean with `JavaTimeModule`, `WRITE_DATES_AS_TIMESTAMPS` disabled, `WRITE_BIGDECIMAL_AS_PLAIN` enabled, `FAIL_ON_UNKNOWN_PROPERTIES` disabled. WHY comment: without plain BigDecimal a two-decimal amount serializes as `1E+2`, which the step-1 schema rejects — and the failure surfaces in a consumer, not here (R7)
- [X] T071 Create `order-service/src/test/java/com/marketplace/orders/PostgresIT.java` — an abstract Testcontainers base starting PostgreSQL 16 only, with `@DynamicPropertySource` wiring the datasource. Deliberately no Kafka: User Story 1 must be testable without a broker
- [X] T072 Verify Flyway applies cleanly: run any test extending `PostgresIT` and confirm both migrations execute and `ddl-auto: validate` raises no mismatch against the entities (FR-023, SC-011)

**Checkpoint**: Schema and entities exist and agree with each other. All three stories unblocked.

---

## Phase 3: User Story 1 — A booking request is accepted durably, or not at all (Priority: P1) 🎯 MVP

**Goal**: `POST /api/orders` writes the order row and its outbox row in one transaction, or writes
neither. Invalid requests and overload are refused without recording anything.

**Independent Test**: Submit a booking request against a running service with PostgreSQL only — no
broker needed — then inspect the database directly for exactly one order and exactly one `PENDING`
outbox row referring to it. Force a failure mid-transaction and confirm neither survives.

### Tests for User Story 1

> Write these first and confirm they fail before implementing.

- [X] T073 [P] [US1] Create `order-service/src/test/java/com/marketplace/orders/api/CreateOrderRequestValidationTest.java` — unit tests rejecting an empty seat list, duplicate seats, a null buyer or show, a negative amount, and an amount whose scale is not exactly 2; each asserting the offending field is named (FR-005, SC-009)
- [X] T074 [P] [US1] Create `order-service/src/test/java/com/marketplace/orders/outbox/OrderPayloadMappingTest.java` — unit test asserting the mapping to `OrderCreated` is total, that `sagaId` equals `orderId`, that `showId` is carried (never a message id), and that the serialized amount is `150.00` rather than `1.5E+2` (R7, data-model.md mapping table)
- [X] T075 [P] [US1] Create `order-service/src/test/java/com/marketplace/orders/OrderAcceptanceIT.java` — extends `PostgresIT`. Asserts one accepted request yields exactly one order and one outbox row sharing an id; that a forced failure writing the outbox row rolls the order back; and that 200 concurrent submissions produce 200 orders and 200 outbox rows with no interleaving loss (FR-007, SC-001, SC-002)
- [X] T076 [P] [US1] Create `order-service/src/test/java/com/marketplace/orders/api/OrderApiIT.java` — asserts 202 with a `Location` header and `status: PENDING`, and that each malformed request returns 400 with the field named and leaves the order count unchanged (FR-002, FR-005, SC-009)
- [X] T077 [P] [US1] Create `order-service/src/test/java/com/marketplace/orders/api/OrderCapacityIT.java` — saturates the connection pool and asserts excess requests return 503 with `Retry-After`, an RFC 7807 body whose `type` differs from the validation problem type, and never 400 or 500 (FR-035, FR-036, SC-016)
- [X] T078 [P] [US1] Create `order-service/src/test/java/com/marketplace/orders/domain/OrderVersionIT.java` — two concurrent updates to one order; asserts the losing writer raises `OptimisticLockingFailureException` rather than silently overwriting (FR-022, R9)

### Implementation for User Story 1

- [X] T079 [P] [US1] Create `order-service/src/main/java/com/marketplace/orders/api/CreateOrderRequest.java` — a record carrying `userId`, `showId`, `seatIds`, `amount`, annotated with Bean Validation constraints matching data-model.md's validation table. Amount is deserialized as `BigDecimal` from a JSON string so no parser can turn it into a binary float (FR-005)
- [X] T080 [P] [US1] Create `order-service/src/main/java/com/marketplace/orders/api/CreateOrderResponse.java` — a record carrying `orderId` and `status`
- [X] T081 [US1] Create `order-service/src/main/java/com/marketplace/orders/outbox/OutboxWriter.java` — builds the `OrderCreated` message, serializes it with the configured `ObjectMapper`, captures the active W3C trace context via Micrometer's `Propagator` into `traceparent`/`tracestate`, and returns an unsaved `OutboxRecord` whose `event_type` is `Topics.ORDER_CREATED` from `common-events` — **never a literal string**, since step 1 put channel names in the contract module precisely so a publisher cannot invent a name no consumer subscribes to. WHY comment: the trace context is a field of the row and never enters the payload, so the frozen contracts stay free of observability concerns (FR-010, FR-025, R4)
- [X] T082 [US1] Create `order-service/src/main/java/com/marketplace/orders/service/OrderAcceptanceService.java` — the single `@Transactional` method that persists the `Order` and the `OutboxRecord` together. Add a WHY comment recording that this is deliberately the only place both tables are written, so FR-007 has exactly one place to be verified (FR-004, FR-007, FR-009)
- [X] T083 [US1] Create `order-service/src/main/java/com/marketplace/orders/api/OrderController.java` — `POST /api/orders` returning 202 with a `Location` header, per `contracts/orders-api.yaml`. A TRADEOFF comment recording why 202 rather than 201: the order exists, the booking does not, and 201 Created would tell a buyer they have seats (FR-001, FR-002)
- [X] T084 [US1] Create `order-service/src/main/java/com/marketplace/orders/api/ApiExceptionHandler.java` — `@RestControllerAdvice` producing RFC 7807 `ProblemDetail` responses with stable `type` URIs: validation → 400 naming the field, pool exhaustion or transaction timeout → 503 with `Retry-After: 1`. WHY comment: FR-036 needs a refusal that is machine-distinguishable from a bad request, and a status code alone is not stable across the step-7 gateway (FR-005, FR-006, FR-036)
- [X] T085 [US1] Add `statement_timeout` to the datasource connection properties in `order-service/src/main/resources/application.yml`, matching the 3-second transaction timeout, so a slow query is cut off by the database rather than only by the application (FR-035, R5)
- [X] T086 [US1] Create `order-service/src/main/java/com/marketplace/orders/api/CapacityMetrics.java` — the `orders.refused.capacity` counter, incremented by the 503 branch of the exception handler, so overload is visible as overload (FR-036, R12)
- [X] T087 [US1] Run quickstart scenarios S1, S4, and S5 against a running service and record the results (SC-001, SC-009, SC-010, SC-016)

**Checkpoint**: Orders are accepted and durably recorded with their outbox rows. Nothing is published
yet — the outbox fills and stays `PENDING`, which is the correct intermediate state.

---

## Phase 4: User Story 2 — Recorded intent reliably reaches the rest of the system (Priority: P2)

**Goal**: The relay drains `PENDING` outbox rows onto their channels, once per row under concurrency,
in per-order recording sequence, parking rows that can never be sent.

**Independent Test**: Insert unsent outbox rows directly into the database, start the service, and
observe the messages appear on the correct channel with the row marked `PUBLISHED` — with no booking
request involved.

### Tests for User Story 2

> These are the specification of the method the developer writes in T099. They fail until it exists.

- [X] T088 [P] [US2] Create `order-service/src/test/java/com/marketplace/orders/KafkaPostgresIT.java` — an abstract Testcontainers base starting PostgreSQL *and* Kafka, with a raw `KafkaConsumer` helper that deserializes using the step-1 contracts. Reading back with an independent consumer is what makes this a wire-format test rather than an in-memory one (SC-008)
- [X] T089 [P] [US2] Create `order-service/src/test/java/com/marketplace/orders/outbox/OutboxRelayIT.java` — guarantees 1–8 of `contracts/outbox-relay.md`: publishes a pending row, keys by saga id, sends the stored payload unchanged, marks published only after acknowledgement, never resends, retains a failed row with `attempts` incremented and `last_error` set, parks at the attempt limit, and does not abandon a batch because one row failed (FR-010, FR-011, FR-016, FR-017, FR-018, FR-028, FR-029)
- [X] T090 [P] [US2] Create `order-service/src/test/java/com/marketplace/orders/outbox/OutboxTracingIT.java` — guarantees 9–10: the stored trace context is injected into the outgoing headers and the publish span continues the accepting request's trace; a row with no stored context is still sent, untraced and without error (FR-026, FR-027, SC-012)
- [X] T091 [P] [US2] Create `order-service/src/test/java/com/marketplace/orders/outbox/OutboxConcurrencyIT.java` — guarantee 11: three relays polling one database over at least 1,000 rows; asserts every row is sent and no row is sent twice (FR-012, FR-013, SC-006)
- [X] T092 [P] [US2] Create `order-service/src/test/java/com/marketplace/orders/outbox/OutboxOrderingIT.java` — guarantee 12: at least 100 orders each with several outbox rows, relayed concurrently; asserts zero ordering inversions within any one order, and that a `PARKED` row halts its own order while every other order continues (FR-014, FR-030, SC-007, SC-013)
- [X] T093 [P] [US2] Create `order-service/src/test/java/com/marketplace/orders/outbox/OutboxRestartRecoveryIT.java` — stops the relay with rows outstanding, restarts it, and asserts all outstanding rows are sent with no manual step (FR-019, SC-005)

### Implementation for User Story 2

- [X] T094 [US2] Add `claimBatch(int limit)` to `order-service/src/main/java/com/marketplace/orders/outbox/OutboxRepository.java` — the native query from research.md R2: earliest `PENDING` row per aggregate, skipping any aggregate with an earlier `PARKED` row, `ORDER BY id LIMIT :limit FOR UPDATE SKIP LOCKED`. Add a WHY comment explaining that ordering lives in the predicate rather than in coordination between relays, and a TRADEOFF comment naming per-aggregate advisory locks as the rejected alternative (FR-012, FR-013, FR-014, FR-015, FR-030)
- [X] T095 [P] [US2] Create `order-service/src/main/java/com/marketplace/orders/config/KafkaProducerConfig.java` — `ProducerFactory` and `KafkaTemplate<String, String>` with `acks=all`, `enable.idempotence=true`, `max.in.flight.requests.per.connection=5`, and `StringSerializer` for key and value. WHY comment: the payload was serialized when the row was written, so the producer must send bytes rather than re-serialize an object (FR-010, R3, R7)
- [X] T096 [P] [US2] Create `order-service/src/main/java/com/marketplace/orders/outbox/OutboxMetrics.java` — the five meters of research.md R12. WHY comment on the gauges: backlog *age* is the meter that matters, since depth spikes harmlessly during a burst while a rising oldest-pending age means the relay is losing ground (FR-031)
- [X] T097 [US2] Create `order-service/src/main/java/com/marketplace/orders/outbox/OutboxRelay.java` — the class, its injected collaborators, the `@Scheduled(fixedDelayString = "${outbox.relay.poll-interval-ms:500}") @Transactional public void pollAndPublish()` signature, and `@EnableScheduling` on the application class. **Leave the method body as `// TODO(developer)`** carrying a comment that states the contract: what it is given, the twelve guarantees it must provide, and why `@Transactional` is load-bearing rather than decorative (spec Clarifications, `contracts/outbox-relay.md`)
- [X] T098 [US2] Write `docs/tasks/T099-outbox-relay-guide.md` — a beginner-level guide to implementing `pollAndPublish`: what an outbox relay is and why it exists, what each collaborator provides, the twelve guarantees restated in plain language, and the five traps from `contracts/outbox-relay.md` with what each one looks like when it goes wrong. Delivered **before** T099 so the developer reads it first
- [X] T099 **[human]** [US2] Implement the body of `pollAndPublish` in `order-service/src/main/java/com/marketplace/orders/outbox/OutboxRelay.java`, working from `contracts/outbox-relay.md` and the guide from T098. Done when T089–T093 pass
- [X] T100 [US2] Review the T099 implementation against the twelve guarantees and the project's comment standards. Keep it if it passes and reads well; rewrite it only if it does not, explaining what was wrong and why (spec Clarifications)
- [X] T101 [US2] Run quickstart scenarios S2, S3, and S7 against a running service and record the results (SC-004, SC-005, SC-007, SC-013)

**Checkpoint**: The saga's first message reaches `order.created`. Step 3 now has something to consume.

---

## Phase 5: User Story 3 — The current state of an order can be inspected (Priority: P3)

**Goal**: `GET /api/orders/{orderId}` returns the order as recorded, or a clear not-found.

**Independent Test**: Accept one booking request, read it back by the returned identifier, and compare
every field against what was submitted.

### Tests for User Story 3

- [X] T102 [P] [US3] Create `order-service/src/test/java/com/marketplace/orders/api/OrderLookupIT.java` — asserts 200 with every submitted field returned unchanged, 404 for an unknown identifier, and 400 for an identifier that is not a well-formed UUID, with 404 and 400 carrying distinct problem `type` URIs (FR-020, FR-021, SC-010)

### Implementation for User Story 3

- [X] T103 [P] [US3] Create `order-service/src/main/java/com/marketplace/orders/api/OrderView.java` — a record carrying `orderId`, `userId`, `showId`, `seatIds`, `amount`, `status`, `createdAt`, `updatedAt`, per `contracts/orders-api.yaml`. Seats are returned sorted so the response is deterministic
- [X] T104 [US3] Add `GET /api/orders/{orderId}` to `order-service/src/main/java/com/marketplace/orders/api/OrderController.java`, returning `OrderView` (FR-020)
- [X] T105 [US3] Extend `order-service/src/main/java/com/marketplace/orders/api/ApiExceptionHandler.java` with an order-not-found problem type mapped to 404, distinct from the malformed-identifier 400 raised by path-variable conversion (FR-021)

**Checkpoint**: All three stories independently functional. The step is demonstrable end to end.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T106 [P] Verify `/actuator/prometheus` exposes all five meters from R12 with the expected names, and that the two gauges read live database state rather than a cached value (FR-024, FR-031)
- [X] T107 [P] Update the profile documentation in `infra/.env` to record that the tracing criterion needs `COMPOSE_PROFILES=core,obs`, since Zipkin is not in the default `core` profile (R4)
- [ ] T108 Run quickstart scenario S6 under `core,obs` and confirm one connected trace spans the accepting request and the later publish, rather than two unrelated traces (FR-026, SC-012)
- [ ] T109 Run quickstart scenario S8 and record acceptance latency, sustained rate, and backlog drain time against the FR-032 and FR-033 targets. Note in the results that this is the interim check and step 9's k6 script supersedes it (SC-003, SC-014, SC-015)
- [X] T110 [P] Update the repository `README.md` with the order-service section: what it owns, its port, and how to submit and read an order
- [ ] T111 [P] Audit every non-obvious line in `order-service/` for a WHY comment rather than a WHAT comment, and confirm each design decision with a real alternative carries a `TRADEOFF:` comment naming what was rejected and why (project constraints)
- [ ] T113 [P] Create `order-service/Dockerfile` — multi-stage build (Maven build stage, JRE 21 runtime stage), exposing 8081 and running the boot jar. Required by the project brief's "each service: own Dockerfile". Wiring it into `infra/docker-compose.yml` stays deferred: the roadmap's open question about whether `make up` builds jars first is answered at step 7, when the first project-built image (Eureka) joins the environment
- [ ] T112 Walk quickstart scenarios S1 through S8 end to end on a clean `make down && make up`, confirming every success criterion in spec.md is either verified or explicitly recorded as deferred to a later step

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (Phase 1)**: T057 is a human task and blocks everything. T058 → T059 → T060 → T061 are strictly sequential.
- **Foundational (Phase 2)**: Depends on Setup. **Blocks all user stories.**
- **User Story 1 (Phase 3)**: Depends on Foundational only. Needs PostgreSQL, not Kafka.
- **User Story 2 (Phase 4)**: Depends on Foundational. Its fixtures insert outbox rows directly, so it is genuinely testable without US1.
- **User Story 3 (Phase 5)**: Depends on Foundational. Independent of US1 and US2.
- **Polish (Phase 6)**: Depends on the stories it verifies.

### Critical path

```text
T057 (human) → T058 → T059 → T060 → T061
                                      │
                                      ▼
              T062..T067 → T068..T071 → T072
                                      │
        ┌─────────────────────────────┼─────────────────────────────┐
        ▼                             ▼                             ▼
   US1 (T073..T087)            US2 (T088..T101)             US3 (T102..T105)
                                      │
                          T097 → T098 → T099 (human) → T100
                                      │
                                      ▼
                              Polish (T106..T112)
```

The single longest dependency is **T099**, the human implementation task. Everything in US2 after it
waits on the developer, so US1 and US3 are the right work to have finished first.

### Within each user story

- Tests before implementation, and confirmed failing before the implementation exists
- Migrations before entities; entities before repositories; repositories before services; services
  before controllers
- `contracts/outbox-relay.md` and the T098 guide before T099 — the contract is the brief for the exercise

### Parallel opportunities

- **Phase 2**: T062–T065 are four different files with no dependency on one another; T068–T070 likewise
- **US1 tests**: T073–T078 are six independent files and can all be written together
- **US2 tests**: T089–T093 are five independent files, all depending only on T088
- **Across stories**: once Phase 2 is done, US1 and US3 can proceed in parallel with everything in US2 up to T097

---

## Parallel Example: User Story 1

```bash
# The six test files, all independent:
Task: "CreateOrderRequestValidationTest in order-service/src/test/java/com/marketplace/orders/api/"
Task: "OrderPayloadMappingTest in order-service/src/test/java/com/marketplace/orders/outbox/"
Task: "OrderAcceptanceIT in order-service/src/test/java/com/marketplace/orders/"
Task: "OrderApiIT in order-service/src/test/java/com/marketplace/orders/api/"
Task: "OrderCapacityIT in order-service/src/test/java/com/marketplace/orders/api/"
Task: "OrderVersionIT in order-service/src/test/java/com/marketplace/orders/domain/"

# Then the two request/response records, also independent:
Task: "CreateOrderRequest in order-service/src/main/java/com/marketplace/orders/api/"
Task: "CreateOrderResponse in order-service/src/main/java/com/marketplace/orders/api/"
```

---

## Implementation Strategy

### MVP (User Story 1 only)

1. Phase 1 Setup → Phase 2 Foundational
2. Phase 3 User Story 1
3. **STOP and VALIDATE**: quickstart S1, S4, S5

This is a genuine increment: orders are accepted and durably recorded with their outbox rows, and the
atomicity guarantee — the one thing no later step can repair — is proven. Messages do not flow yet, so
the outbox fills and stays `PENDING`. That is the correct intermediate state, not a broken one.

### Incremental delivery

1. Setup + Foundational → the module is in the build and the schema is real
2. **+ US1** → orders accepted atomically → validate → commit
3. **+ US3** → orders readable → validate → commit *(cheap, and it makes US2 far easier to observe)*
4. **+ US2** → messages flow → validate → **step 3 unblocked**
5. Polish → tracing, metrics, and the performance budget confirmed

US3 before US2 is a deliberate reordering against the priority numbers: it is four small tasks and it
gives you `GET /api/orders/{id}` to watch while debugging the relay. Priorities express value, not
build order.

---

## Notes

- **One task, one commit.** Per the project workflow each task is committed on its own, so the history
  reads as a step-by-step record of how the service was built.
- **Each task carries a beginner-level explanation** in `docs/tasks/T0NN-<slug>.md`, written for
  someone new to the technology rather than for an experienced engineer, and committed with the code
  it describes. T098 is the exception in kind, not in form: it is written *before* its task rather
  than after, because it is a brief rather than a record.
- **[human] tasks are yours**: T057 (scaffolding, Constitution Principle V) and T099 (the relay body, a
  deliberate exercise). Everything else is mine.
- Relay tests failing between T089 and T099 is the intended state, not a regression.
- `[P]` means different files with no incomplete dependency between them.
