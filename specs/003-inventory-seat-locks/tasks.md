---

description: "Task list for Seat Holds & the Inventory Authority"
---

# Tasks: Seat Holds & the Inventory Authority

**Input**: Design documents from `/specs/003-inventory-seat-locks/`

**Prerequisites**: plan.md, spec.md, research.md, data-model.md, contracts/, quickstart.md

**Tests**: Test tasks ARE included. Constitution Principle II names ticket reservation explicitly as
requiring tests that exercise concurrent execution, and the spec makes that a requirement in its own
right (FR-037 – FR-042). A happy-path test is not acceptable evidence for this feature.

**Organization**: Grouped by user story so each is independently implementable and testable.

## Format: `[ID] [P?] [Story] Description`

- **[P]**: Can run in parallel (different files, no dependency on incomplete work)
- **[Story]**: US1, US2, US3 — maps to the user stories in spec.md
- **[human]**: The developer performs this, not the assistant (Constitution Principle V, or a
  deliberate learning exercise)
- Every task names an exact file path

## Numbering

Task ids continue from step 2, which ended at T113. Numbering is global across build steps so that
`docs/tasks/T0NN-*.md` filenames never collide and the commit history reads as one continuous build
log.

## Path Conventions

Multi-module Maven project, build root at the repository root. This step adds one module,
`inventory-service/`, with sources under `inventory-service/src/main/java/com/marketplace/inventory/`.

---

## Phase 1: Setup (Shared Infrastructure)

**Purpose**: Bring the module into the build so anything can compile.

- [X] T114 **[human]** Generate `inventory-service` at [start.spring.io](https://start.spring.io) using the exact settings in `specs/003-inventory-seat-locks/quickstart.md` §0 — Maven, Java 21, Spring Boot 3.3.x, group `com.marketplace`, artifact `inventory-service`, package `com.marketplace.inventory`, dependencies Web / Data JPA / PostgreSQL Driver / Data Redis / Kafka / Flyway / Actuator / Validation. Unzip into the repository root and delete the generated `mvnw`, `mvnw.cmd`, `.mvn/`, and `.gitignore` (Constitution Principle V)
- [X] T115 Adapt `inventory-service/pom.xml`: replace the `spring-boot-starter-parent` parent with `com.marketplace:ticket-marketplace:0.0.1-SNAPSHOT`, delete the now-inherited `<properties>` and version tags, rewrite any Boot 4 starter names to their 3.3 equivalents exactly as `order-service/pom.xml` documents, and add `common-events`, `spring-boot-starter-data-redis`, `micrometer-tracing-bridge-brave`, `zipkin-reporter-brave`, `brave-propagation-tracecontext`, `micrometer-registry-prometheus`, `lombok` (optional scope), and test-scope `testcontainers-postgresql`, `testcontainers-kafka`, `testcontainers-junit-jupiter`, `awaitility`. Keep `spring-boot-maven-plugin` in this module
- [X] T116 Add `<module>inventory-service</module>` to the root `pom.xml` module list, immediately after `order-service`
- [X] T117 Create `inventory-service/src/main/resources/application.yml`: `server.port: 8082`; datasource at `localhost:5432/marketplace` with HikariCP `maximum-pool-size: 12` and a WHY comment recording the `max_connections=50` budget shared with the other services (R12); `spring.flyway.schemas`/`default-schema: inventory` and `hibernate.default_schema: inventory` with a WHY comment recording that two services sharing one `flyway_schema_history` would fight over it; `ddl-auto: validate`; `spring.data.redis` host/port and a `1s` command timeout; Kafka bootstrap, consumer group `inventory-service`, and **`spring.kafka.listener.auto-startup: false`** with a WHY comment pointing at the rebuild ordering (R4, FR-015); `inventory.hold.*`, `inventory.consumer.*`, `inventory.sweeper.*` and `outbox.relay.*` properties; actuator exposing `health,info,prometheus`; tracing sampling `1.0` and the Zipkin endpoint
- [X] T118 Verify the module joins the build: `./mvnw -q -pl inventory-service -am verify` succeeds on the generated skeleton

**Checkpoint**: `inventory-service` compiles as part of the root build. No behaviour yet.

---

## Phase 2: Foundational (Blocking Prerequisites)

**Purpose**: Schema, entities, configuration, and the ported outbox — everything all three stories need.

**⚠️ CRITICAL**: No user story work can begin until this phase is complete.

### Schema

- [X] T119 [P] Create `inventory-service/src/main/resources/db/migration/V1__create_seating_plan.sql` — `shows` and `show_seats` per data-model.md, plus the seed: one show named `Load Test Hall` with exactly the ten seats the step-9 load test contends over, and one further show so per-show scoping of seat labels is exercisable. WHY comment on the composite primary key: a seat label is meaningful only relative to its show, so `A1` in two shows is two different seats (FR-033, FR-034, FR-035)
- [X] T120 [P] Create `inventory-service/src/main/resources/db/migration/V2__create_reservations.sql` — `reservations` with its `@Version` column, the four-state CHECK constraint and `order_id UNIQUE`; `reservation_seats` with the denormalised `show_id` and nullable `released_at`; and `ux_reservation_seat_live`, the partial unique index on `(show_id, seat_label) WHERE released_at IS NULL`. WHY comment on the index: it is the guarantee that survives Redis being wrong, and it is the concrete reason the `EXPIRED` state exists — a predicate using `now()` is not immutable and PostgreSQL will refuse it (FR-017, FR-020, R5)
- [X] T121 [P] Create `inventory-service/src/main/resources/db/migration/V3__create_processed_messages.sql` — `processed_messages` keyed on `(message_id, consumer_name)`. WHY comment recording both deviations from the original brief: the composite key, because a single-column key silently locks every other consumer out of a message it has not seen; and the naming, because step 1 removed the word "event" as a field name and this is the one table whose entire job is identifying messages (FR-028, FR-029, R7)
- [X] T122 [P] Create `inventory-service/src/main/resources/db/migration/V4__create_outbox.sql` — the `outbox` table exactly as `order-service` defines it, including both CHECK constraints and both partial indexes. Add a TRADEOFF comment recording that this is a copy rather than a shared module, that the constitution prefers duplication to premature abstraction, and that extraction is revisited if a third service needs one (R8)

### Domain

- [X] T123 [P] Create `inventory-service/src/main/java/com/marketplace/inventory/domain/ReservationStatus.java` — enum `HELD`, `EXPIRED`, `COMMITTED`, `RELEASED`, with a comment recording that only the first two are reachable in this step and why all four are declared now
- [X] T124 [P] Create `inventory-service/src/main/java/com/marketplace/inventory/outbox/OutboxStatus.java` — enum `PENDING`, `PUBLISHED`, `PARKED`, ported from `order-service`
- [X] T125 [P] Create `Show.java`, `ShowSeat.java`, `ShowSeatId.java` and `SeatingPlanRepository.java` in `inventory-service/src/main/java/com/marketplace/inventory/domain/` — the seating plan, with a method answering "do all these labels exist in this show" in one query rather than N (FR-033)
- [X] T126 Create `inventory-service/src/main/java/com/marketplace/inventory/domain/Reservation.java` — JPA entity with `@Version`, `lock_expires_at`, and `@PrePersist`/`@PreUpdate` timestamps (FR-010, FR-012)
- [X] T127 Create `inventory-service/src/main/java/com/marketplace/inventory/domain/ReservationSeat.java` — entity over `reservation_seats` carrying `show_id` and `released_at`. WHY comment explaining that `released_at IS NULL` answers "is this seat claimed", which is true for both `HELD` and `COMMITTED`, so it is a projection of the parent's status rather than a duplicate of it (FR-020, R5)
- [X] T128 Create `inventory-service/src/main/java/com/marketplace/inventory/domain/ReservationRepository.java` — lookups by order id, the "live reservations covering these seats" query the inline retirement needs (FR-018), and the "held and unlapsed" query the startup rebuild needs (FR-015)
- [X] T129 [P] Create `ProcessedMessage.java`, `ProcessedMessageId.java` (`@Embeddable`) and `ProcessedMessageRepository.java` in `inventory-service/src/main/java/com/marketplace/inventory/consume/`

### Ported outbox

- [X] T130 [P] Create `inventory-service/src/main/java/com/marketplace/inventory/outbox/OutboxRecord.java` — ported from `order-service`, including the comment recording that identity order is not commit order
- [X] T131 [P] Create `inventory-service/src/main/java/com/marketplace/inventory/outbox/OutboxRepository.java` — the `claimBatch(int)` native query ported unchanged, keeping its WHY and TRADEOFF comments (FR-026)
- [X] T132 [P] Create `inventory-service/src/main/java/com/marketplace/inventory/outbox/OutboxMetrics.java` — ported, retagged for this service
- [X] T133 Create `inventory-service/src/main/java/com/marketplace/inventory/outbox/OutboxRelay.java` — `pollAndPublish` ported **implemented**, not re-stubbed. Add a comment recording that this was the step-2 exercise and repeating it would teach nothing; the exercises in this step are the Lua scripts (R8, R11)

### Configuration

- [X] T134 [P] Create `inventory-service/src/main/java/com/marketplace/inventory/config/JacksonConfig.java` — ported: `JavaTimeModule`, `WRITE_DATES_AS_TIMESTAMPS` off, `WRITE_BIGDECIMAL_AS_PLAIN` on, `FAIL_ON_UNKNOWN_PROPERTIES` off
- [X] T135 [P] Create `inventory-service/src/main/java/com/marketplace/inventory/config/RedisConfig.java` — a `StringRedisTemplate` and the command timeout. WHY comment: the Redis call is on the buyer's critical path, so an unbounded client timeout would violate the constitution's no-unbounded-blocking rule
- [X] T136 [P] Create `inventory-service/src/main/java/com/marketplace/inventory/config/KafkaProducerConfig.java` — ported: `acks=all`, idempotent, `StringSerializer` for key and value
- [X] T137 [P] Create `inventory-service/src/main/java/com/marketplace/inventory/config/TracingConfig.java` — ported, keeping the javadoc explaining why the `Propagation.Factory` bean is declared directly rather than via `management.tracing.propagation.type`

### Test foundations

- [X] T138 Create `inventory-service/src/test/java/com/marketplace/inventory/InventoryIT.java` — abstract Testcontainers base starting PostgreSQL 16 **and** Redis 7, with `@DynamicPropertySource` wiring both and a shrunk connection pool. Deliberately no Kafka: every contention assertion in User Story 1 must be testable without a broker. WHY comment on using real Redis rather than an embedded fake: script atomicity is the property under test and a fake would answer questions about the fake (FR-039)
- [X] T139 Create `inventory-service/src/test/java/com/marketplace/inventory/InventoryKafkaIT.java` — extends `InventoryIT`, adds Kafka with `KAFKA_AUTO_CREATE_TOPICS_ENABLE=false`, provisions `order.created`, `seats.reserved`, `seats.rejected` and `order.created.DLT` at three partitions, and offers a raw `KafkaConsumer` helper deserializing with the step-1 contracts (FR-042, SC-009)
- [X] T140 [P] Create `inventory-service/src/test/java/com/marketplace/inventory/SeatingPlanFixture.java` — a helper that provisions a show with N seats for a test's exclusive use. WHY comment: SC-003 needs at least 500 distinct seats and the seeded plan carries about eleven, and tests sharing one pool make failures order-dependent (FR-036, FR-041)
- [X] T141 Verify Flyway applies cleanly into the `inventory` schema and `ddl-auto: validate` raises no mismatch against the entities, without disturbing `order-service`'s own migration history (SC-012)

**Checkpoint**: schema, entities, config and the outbox are in place. Nothing decides anything yet.

---

## Phase 3: User Story 1 — Seats are held for exactly one order, even under contention (Priority: P1) 🎯 MVP

**Goal**: A booking request for seats that are all free results in an all-or-nothing hold, a durable
reservation, and a `seats.reserved` announcement. Under contention, exactly one order wins each seat.

**Independent Test**: Call `ReservationService` directly from hundreds of threads against real Redis
and real PostgreSQL — no broker, no order-service. Assert that the number of successful holds equals
the number of seats available and that every seat appears in exactly one hold.

### Tests for User Story 1 ⚠️

> Write these FIRST. They fail until T152–T157 land, and the Lua tests fail until T155. That is the
> intended state, not a regression.

- [X] T142 [P] [US1] Create `inventory-service/src/test/java/com/marketplace/inventory/seats/SeatKeyTest.java` — unit test asserting the key is built from `showId()` and the seat label, that it is stable across two different messages for the same order, and that `messageId()` appears nowhere in it (FR-007, R3)
- [X] T143 [P] [US1] Create `inventory-service/src/test/java/com/marketplace/inventory/seats/SeatLockScriptIT.java` — the ten guarantees of `contracts/seat-lock-scripts.md`: all-free acquires everything, any-held acquires nothing, a key held by the same order counts as acquirable, every key carries the TTL, unnamed keys are untouched, release deletes only own keys, release leaves another order's key alone, release is idempotent, and both scripts return a `Long`
- [X] T144 [P] [US1] Create `inventory-service/src/test/java/com/marketplace/inventory/ReservationContentionIT.java` — SC-001: 1,000 threads released by a `CountDownLatch` against a 10-seat pool; asserts exactly 10 granted, exactly 990 refused, zero seats in two holds, repeated 20 times with no run deviating (FR-037, FR-038)
- [X] T145 [P] [US1] Create `inventory-service/src/test/java/com/marketplace/inventory/ReservationPartialOverlapIT.java` — SC-002: at least 500 concurrent requests over partially overlapping seat sets; asserts zero partial holds — every granted request holds every seat it asked for, every refused request holds none
- [X] T146 [P] [US1] Create `inventory-service/src/test/java/com/marketplace/inventory/ReservationDisjointIT.java` — SC-003: at least 500 concurrent requests over entirely disjoint seat sets from a pool the test provisions itself; asserts 100% are granted. WHY this test matters as much as SC-001: it catches a lock that serialises everything and looks correct precisely because nothing double-books (FR-041)
- [X] T147 [P] [US1] Create `inventory-service/src/test/java/com/marketplace/inventory/LiveSeatConstraintIT.java` — SC-017: inserts two live `reservation_seats` rows for one seat **with Redis bypassed entirely**, and asserts the unique index rejects the second. Testing this through the normal path would only prove Redis works (FR-020)
- [X] T148 [P] [US1] Create `inventory-service/src/test/java/com/marketplace/inventory/ReservationVersionIT.java` — SC-011: two concurrent updates to one reservation; asserts one succeeds, the loser is detected, retried exactly once, and a second failure surfaces as a processing failure rather than as a seat refusal (FR-012, FR-013)
- [X] T149 [P] [US1] Create `inventory-service/src/test/java/com/marketplace/inventory/LapsedRebookingIT.java` — SC-016: with the sweeper disabled, rebook a seat whose previous hold has lapsed; asserts it succeeds on the first attempt and the previous reservation is now `EXPIRED`. The sweeper being off is the point — correctness must not depend on it (FR-018, R6)
- [X] T150 [P] [US1] Create `inventory-service/src/test/java/com/marketplace/inventory/outbox/OutcomeMappingTest.java` — unit test asserting the mapping to `SeatsReserved` is total, that `sagaId` equals `orderId`, that seats are sorted, and that `lockExpiresAt` is strictly after `occurredAt` because both are derived from one instant (FR-009, data-model.md mapping table)

### Implementation for User Story 1

- [X] T151 [US1] Create `inventory-service/src/main/java/com/marketplace/inventory/seats/SeatKey.java` — builds `seat:{showId}:{seatId}`. WHY comment recording that the brief's `{eventId}` predates step 1's rename, and that a hold keyed by message identity is unique per delivery, so a redelivered request would contend with nothing and take a second hold on a seat it already holds (FR-007, R3)
- [X] T152 [US1] Create `inventory-service/src/main/resources/scripts/lock_seats.lua` and `release_seats.lua` as **empty files carrying only a header comment** stating each script's contract — `KEYS`, `ARGV`, return value, and the guarantees from `contracts/seat-lock-scripts.md` (CLAUDE.md requirement 2, R11)
- [X] T153 [US1] Create `inventory-service/src/main/java/com/marketplace/inventory/seats/SeatLockScripts.java` — the two `DefaultRedisScript<Long>` beans loading those files from the classpath. WHY comment on the `Long` result type: Lua `false` converts to a Redis nil reply, so the scripts must return the numbers `1`/`0` rather than booleans
- [X] T154 [US1] Create `inventory-service/src/main/java/com/marketplace/inventory/seats/SeatLockStore.java` — the calling method: builds the keys, evaluates the script, and translates the numeric result into a boolean outcome. TRADEOFF comment recording that Redis has no rollback, so the script runs inside the transaction but is not part of it — seats briefly unavailable is the accepted direction of failure, and database-first would fail the other way
- [X] T155 [US1] Write `docs/tasks/T156-seat-lock-scripts-guide.md` — a beginner-level guide to writing the two scripts: what atomicity means here and why Redis provides it, what a check-then-act race actually looks like with two buyers, why partial acquisition is worse than it sounds, the self-owned case, the unconditional-`DEL` bug, and how to run just `SeatLockScriptIT`. Delivered **before** T156 so it is read first
- [ ] T156 **[human]** [US1] Implement the bodies of `lock_seats.lua` and `release_seats.lua`, working from `contracts/seat-lock-scripts.md` and the guide from T155. Done when `SeatLockScriptIT` passes
- [ ] T157 [US1] Review the T156 implementation against the ten guarantees and the project's comment standards. Keep it if it passes and reads well; rewrite it only if it does not, explaining what was wrong and why
- [X] T158 [P] [US1] Create `inventory-service/src/main/java/com/marketplace/inventory/service/ReservationOutcome.java` — a sealed interface permitting `Reserved` and `Rejected(RejectionReason)`, so the switch that maps an outcome to a message is exhaustive and adding a cause becomes a compile error rather than a silently unhandled branch
- [X] T159 [US1] Create `inventory-service/src/main/java/com/marketplace/inventory/outbox/OutboxWriter.java` — builds `SeatsReserved` or `SeatsRejected`, serializes with the configured `ObjectMapper`, captures the active trace context into `traceparent`/`tracestate`, and sets `event_type` from `Topics` — never a literal. WHY comment: `occurredAt` and `lockExpiresAt` must come from one instant, because the contract requires the lapse to fall strictly after the timestamp and would otherwise throw when the message is constructed (FR-009)
- [ ] T160 [US1] Create `inventory-service/src/main/java/com/marketplace/inventory/service/ReservationService.java` — the single `@Transactional` method that retires any lapsed reservation covering the requested seats, takes the hold, and records the reservation, its seats and the outbox row together. Handles the happy path and `SEATS_ALREADY_HELD` only; the seating-plan causes arrive in US2. Add a WHY comment recording that this is deliberately the only place all four tables are written, so FR-025's atomicity claim has exactly one place to be verified (FR-018, FR-025)
- [ ] T161 [US1] Create `inventory-service/src/main/java/com/marketplace/inventory/sweeper/LapsedReservationSweeper.java` — `@Scheduled`, retires lapsed reservations nobody has contended for. WHY comment: this is tidy-up, never load-bearing — a sweeper that is late, stopped or absent must not cause a single booking to fail, which is what T149 proves (FR-019, R6)
- [ ] T162 [US1] Create `inventory-service/src/test/java/com/marketplace/inventory/outbox/OutboxRelayPortIT.java` — proves the ported relay works in this module: a pending row reaches its channel keyed by saga id and is marked published. TRADEOFF comment recording that the exhaustive twelve-guarantee suite lives in `order-service` against identical code, so re-proving all twelve here would be duplicated effort rather than added confidence
- [ ] T163 [US1] Run quickstart scenarios S1, S4, S9 and S10 and record the results (SC-001, SC-002, SC-003, SC-005, SC-016, SC-017)

**Checkpoint**: seats are held correctly under contention and announced. Refusals for unknown shows and
unknown seats are not distinguished yet, and nothing consumes from Kafka.

---

## Phase 4: User Story 2 — A request that cannot be honoured is refused with a stated cause (Priority: P2)

**Goal**: The three frozen refusal causes are each produced by the condition that names them, and
nothing is held in any of the three cases.

**Independent Test**: Drive three deliberately unhonourable requests — an unknown show, a real show
with a fabricated seat label, and seats already held — and assert each produces its own distinct cause
with no seat state changed.

### Tests for User Story 2 ⚠️

- [ ] T164 [P] [US2] Create `inventory-service/src/test/java/com/marketplace/inventory/ReservationRejectionIT.java` — SC-008: one deliberately constructed request per cause, asserting each cause is produced by its own condition and by no other; that a refusal reports the **full** requested seat set rather than only the unavailable seats; and that after every refusal no seat from the request is held, including seats that were free at the moment of the attempt (FR-023)

### Implementation for User Story 2

- [ ] T165 [US2] Extend `ReservationService` with the seating-plan checks: an unknown show yields `SHOW_NOT_FOUND` before any seat is examined, and a real show with a label absent from its plan yields `SEATS_NOT_FOUND`. WHY comment recording why the two are distinct: one never succeeds on retry and the other might, and collapsing them has clients retrying forever against a seat that will never exist (FR-023, FR-033)
- [ ] T166 [P] [US2] Create `inventory-service/src/main/java/com/marketplace/inventory/service/DecisionMetrics.java` — `inventory.holds.granted`, `inventory.holds.refused` tagged by cause, and `inventory.decision.duration`. WHY comment on the tag: without it a service refusing everything and a service refusing nothing produce identical graphs (FR-045, R13)
- [ ] T167 [US2] Run quickstart scenarios S2 and S3 and record the results (SC-002, SC-008)

**Checkpoint**: every reachable outcome is now produced with a truthful cause. Still no consumer.

---

## Phase 5: User Story 3 — A message delivered twice changes the world once (Priority: P3)

**Goal**: The delivery path — consuming `order.created`, suppressing duplicates without swallowing
outcomes, routing undecidable messages aside, and rebuilding Redis before any of it starts.

**Independent Test**: Consume the identical booking message ten times and assert the seat state, the
reservation and the set of announcements are each identical to what a single delivery produces.

### Tests for User Story 3 ⚠️

- [ ] T168 [P] [US3] Create `inventory-service/src/test/java/com/marketplace/inventory/consume/IdempotencyIT.java` — SC-006: ten identical deliveries produce exactly one reservation, one set of holds and one outcome; an interrupted delivery still produces its outcome on redelivery; and two different messages never suppress one another (FR-030, FR-031)
- [ ] T169 [P] [US3] Create `inventory-service/src/test/java/com/marketplace/inventory/consume/UndecidableRequestIT.java` — SC-018/SC-019: with the stores unreachable, asserts **zero** outcome messages are produced; after recovery every affected request is decided with no manual replay; a message that can never be decided reaches `order.created.DLT` within its attempt limit and is visible as a metric; and an unrecognised `schemaVersion` is dead-lettered immediately rather than retried (FR-003, FR-047, FR-048, FR-049)
- [ ] T170 [P] [US3] Create `inventory-service/src/test/java/com/marketplace/inventory/startup/SeatLockRebuildIT.java` — SC-013/SC-014: flush Redis with a live hold in place, restart the context, and assert the hold is observable **before** the first message is judged, that a competing request for it is refused, and that the restored key's TTL is smaller than a full lifetime rather than reset to one (FR-015, FR-016)
- [ ] T171 [P] [US3] Create `inventory-service/src/test/java/com/marketplace/inventory/SagaEndToEndIT.java` — SC-009: a real `OrderCreated` published to `order.created` produces a real `seats.reserved` read back by an independent consumer, keyed by the saga id and deserializing into the value this service decided. This is the producer/consumer contract test the constitution requires, and the first one in the project with both halves present (FR-042)

### Implementation for User Story 3

- [ ] T172 [US3] Create `inventory-service/src/main/java/com/marketplace/inventory/consume/IdempotencyGuard.java` — the class, its collaborators and the method signature, with the body left as `// TODO(developer)` carrying a comment stating the contract: insert-in-the-same-transaction, catch `DataIntegrityViolationException`, and why the guard must run before the hold attempt (CLAUDE.md requirement 3, FR-028, FR-032)
- [ ] T173 [US3] Write `docs/tasks/T174-idempotency-guard-guide.md` — a beginner-level guide: what at-least-once delivery means, why a consumer rather than a producer solves it, why the insert must share the state change's transaction, why catching the constraint violation is the check rather than a `SELECT` first, and the self-contention trap if the guard runs after the hold. Delivered **before** T174
- [ ] T174 **[human]** [US3] Implement the body of `IdempotencyGuard`, working from the contract and the guide from T173. Done when `IdempotencyIT` passes
- [ ] T175 [US3] Review the T174 implementation against the contract and the project's comment standards. Keep it if it passes and reads well; rewrite it only if it does not, explaining what was wrong and why
- [ ] T176 [P] [US3] Create `inventory-service/src/main/java/com/marketplace/inventory/consume/UnknownSchemaVersionException.java` — thrown when `schemaVersion` is not recognised, and classified as non-retryable so it reaches the dead-letter channel immediately rather than being retried four times against a message whose shape will never change (FR-003)
- [ ] T177 [US3] Create `inventory-service/src/main/java/com/marketplace/inventory/config/KafkaConsumerConfig.java` — `ErrorHandlingDeserializer`, and a `DefaultErrorHandler` with bounded `ExponentialBackOff` recovering to a `DeadLetterPublishingRecoverer`. **Override the destination resolver to use `Topics.dlt(topic)`**: the default `-dlt` suffix produces `order.created-dlt`, which step 1 never provisioned, and with `auto.create.topics.enable=false` the recovery itself then fails and the message is lost. Register `UnknownSchemaVersionException` and deserialization failures as non-retryable (FR-047, FR-048, R9)
- [ ] T178 [US3] Create `inventory-service/src/main/java/com/marketplace/inventory/consume/OrderCreatedListener.java` — `@KafkaListener` on `Topics.ORDER_CREATED` that checks the schema version, then hands the message to `ReservationService`. WHY comment recording that `@Transactional` belongs on the service method rather than here, so the error handler's own bookkeeping stays outside the transaction (contracts/inventory-consumer.md)
- [ ] T179 [US3] Create `inventory-service/src/main/java/com/marketplace/inventory/startup/SeatLockRebuilder.java` — an `ApplicationRunner` that replays held, unlapsed reservations into Redis with `SET … PXAT` and only then calls `KafkaListenerEndpointRegistry#start()`. WHY comment: `PXAT` sets an absolute expiry, so a restart cannot silently extend a hold past what was announced; and consuming before the replay completes is precisely a double-booking, by a service that looks perfectly healthy while doing it (FR-015, FR-016, R4)
- [ ] T180 [P] [US3] Add `inventory.messages.deadlettered` to `DecisionMetrics`, incremented by the dead-letter recoverer, so a service failing to decide anything is distinguishable from a service receiving nothing (FR-050, R13)
- [ ] T181 [US3] Run quickstart scenarios S5, S6 and S7 and record the results (SC-006, SC-007, SC-013, SC-014, SC-018, SC-019)

**Checkpoint**: the saga runs end to end from `POST /api/orders` to `seats.reserved`. Step 4 is unblocked.

---

## Phase 6: Polish & Cross-Cutting Concerns

- [ ] T182 [P] Verify `/actuator/prometheus` exposes all five meters from R13 under the expected names, and that the refusal counter carries a `cause` tag for each of the three causes (FR-045)
- [ ] T183 [P] Create `inventory-service/Dockerfile` — multi-stage build mirroring `order-service`'s, exposing 8082 and running the boot jar
- [ ] T184 [P] Update the repository `README.md` with the inventory-service section: what it owns, its port, the Redis key format, and how to watch a hold appear and lapse
- [ ] T185 [P] Audit every non-obvious line in `inventory-service/` for a WHY comment rather than a WHAT comment, and confirm each design decision with a real alternative carries a `TRADEOFF:` comment naming what was rejected and why
- [ ] T186 Run quickstart scenario S8 under `COMPOSE_PROFILES=core,obs` and confirm **one** connected trace spans order-service's acceptance, its publish, this service's decision and its own publish — not four unrelated traces (SC-015)
- [ ] T187 Walk quickstart scenarios S1 through S10 end to end on a clean `make down && make up`, confirming every success criterion in spec.md is either verified or explicitly recorded as deferred to a later step

---

## Dependencies & Execution Order

### Phase dependencies

- **Setup (Phase 1)**: T114 blocks everything — nothing exists until the module does
- **Foundational (Phase 2)**: depends on Setup; blocks all three user stories
- **US1 (Phase 3)**: depends on Foundational only
- **US2 (Phase 4)**: depends on US1 — it extends `ReservationService`, which US1 creates
- **US3 (Phase 5)**: depends on US1 for the service it calls; independent of US2
- **Polish (Phase 6)**: depends on all three stories

### Within each story

- Tests are written and failing before the implementation that satisfies them
- Migrations before entities; entities before repositories; repositories before services
- `contracts/seat-lock-scripts.md` and the T155 guide before T156 — the contract is the brief for the exercise
- Likewise `contracts/inventory-consumer.md` and the T173 guide before T174

### Story independence, honestly stated

US2 is **not** fully independent of US1: both are branches of one decision method, and splitting them
across two service classes to manufacture independence would scatter one decision across two files for
no benefit. US2 is still independently *testable* — T164 asserts only refusal causes — and
independently *deliverable*, since US1 ships a working system without it.

### Parallel opportunities

- **Phase 2**: T119–T122 are four migrations; T123/T124 two enums; T129–T132 and T134–T137 are all
  distinct files
- **US1 tests**: T142–T150 are nine independent files and can all be written together
- **US3 tests**: T168–T171 are four independent files
- **Across stories**: once US1 reaches T160, US2 and US3 can proceed in parallel

---

## Parallel Example: User Story 1

```bash
# The nine test files, all independent:
Task: "SeatKeyTest in inventory-service/src/test/java/com/marketplace/inventory/seats/"
Task: "SeatLockScriptIT in inventory-service/src/test/java/com/marketplace/inventory/seats/"
Task: "ReservationContentionIT in inventory-service/src/test/java/com/marketplace/inventory/"
Task: "ReservationPartialOverlapIT in inventory-service/src/test/java/com/marketplace/inventory/"
Task: "ReservationDisjointIT in inventory-service/src/test/java/com/marketplace/inventory/"
Task: "LiveSeatConstraintIT in inventory-service/src/test/java/com/marketplace/inventory/"
Task: "ReservationVersionIT in inventory-service/src/test/java/com/marketplace/inventory/"
Task: "LapsedRebookingIT in inventory-service/src/test/java/com/marketplace/inventory/"
Task: "OutcomeMappingTest in inventory-service/src/test/java/com/marketplace/inventory/outbox/"

# Then the four migrations, also independent:
Task: "V1__create_seating_plan.sql in inventory-service/src/main/resources/db/migration/"
Task: "V2__create_reservations.sql in inventory-service/src/main/resources/db/migration/"
Task: "V3__create_processed_messages.sql in inventory-service/src/main/resources/db/migration/"
Task: "V4__create_outbox.sql in inventory-service/src/main/resources/db/migration/"
```

---

## Implementation Strategy

### MVP (User Story 1 only)

1. Phase 1 Setup → Phase 2 Foundational
2. Phase 3 User Story 1
3. **STOP and VALIDATE**: quickstart S1, S4, S9, S10

This is a genuine increment, and it is the one that matters: seats are held atomically under
contention, and the double-booking that no later step could detect or repair is proven impossible at
1,000-way concurrency. Nothing consumes from Kafka yet, so requests arrive by calling the service
directly. That is the correct intermediate state, not a broken one.

### Incremental delivery

1. Setup + Foundational → the module is in the build and the schema is real
2. **+ US1** → seats held correctly under contention → validate → commit *(MVP)*
3. **+ US2** → every refusal states a truthful cause → validate → commit
4. **+ US3** → the saga runs end to end → validate → **step 4 unblocked**
5. Polish → tracing, metrics, Docker, README

US2 before US3 follows the priority numbers here, unlike step 2's reordering: US2 is two tasks and it
makes US3's end-to-end assertions meaningful, since a saga that can only ever succeed exercises half
the paths.

---

## Notes

- **One task, one commit.** Per the project workflow each task is committed on its own, so the history
  reads as a step-by-step record of how the service was built.
- **Each task carries a beginner-level explanation** in `docs/tasks/T0NN-<slug>.md`, written for
  someone new to the technology rather than for an experienced engineer, and committed with the code it
  describes. T155 and T173 are exceptions in kind, not in form: they are written *before* their tasks,
  because they are briefs rather than records.
- **[human] tasks are yours**: T114 (scaffolding, Constitution Principle V), T156 (the Lua scripts) and
  T174 (the idempotency guard). Everything else is mine.
- **Failing tests between T143 and T157, and between T168 and T175, are the intended state.** That is
  how the constitution's "fail before, pass after" rule is satisfied structurally rather than
  ceremonially.
- **Run the whole suite before calling a task done**, not only the tests that look topically relevant —
  `order-service`'s tests share the same PostgreSQL instance and the same build.
- `[P]` means different files with no incomplete dependency between them.
