# Phase 0 Research: Seat Holds & the Inventory Authority

**Feature**: `003-inventory-seat-locks` | **Date**: 2026-08-27

Findings that shape the design. Each records what was chosen, why, and what was rejected. Referenced
from [plan.md](./plan.md) as R1…R14.

---

## R1 — Why the hold must be one round trip, not a check followed by a take

**Decision**: All-or-nothing seat acquisition is performed by a single Lua script evaluated by Redis,
via `DefaultRedisScript<Long>`.

**Rationale**: Redis executes a script atomically — it is single-threaded, and a script runs to
completion before any other command is served. That is the only property that makes "check every seat
is free, then take every seat" correct. Any client-side sequence has a window between the check and
the take in which another client does the same thing, and both conclude the seats were free.

A `SETNX` loop is the obvious alternative and is wrong in two distinct ways. Partial acquisition is
observable: after locking A1 and A2 but before A3, a competing request for A2 sees it taken, so a
request that will ultimately be rolled back has already caused someone else's refusal. And the rollback
itself is not reliable — the client can die mid-loop, leaving seats held by an order that no longer
exists (only the TTL saves it). `WATCH`/`MULTI` was also rejected: it detects the conflict but requires
a client-side retry loop, which under 1,000 contenders on 10 seats degenerates into a livelock where
most attempts abort and retry repeatedly.

**Alternatives considered**: `SETNX` per seat with compensating deletes; `WATCH`/`MULTI`/`EXEC`
optimistic transactions; a Redlock-style distributed lock over the whole show (correct but serialises
every booking for a show through one lock, destroying the disjoint-seat concurrency SC-003 demands).

---

## R2 — The script's contract, including the self-owned case

**Decision**: `lock_seats.lua` receives the seat keys as `KEYS` and `[orderId, ttlMillis]` as `ARGV`.
It returns `1` if every key was free **or already held by this same order**, having set them all; `0`
otherwise, having set nothing.

**Rationale**: Treating a key already carrying this order's id as acquirable makes the script
idempotent, so re-running it is harmless. FR-032 already prevents the ordinary duplicate from reaching
the script at all, but a script that is safe to re-run costs one comparison and removes a whole class
of self-contention bug — including the one where a retry after a transient Postgres error finds the
seats it locked microseconds earlier and refuses itself.

`release_seats.lua` receives the keys and `[orderId]`, and deletes **only** keys whose value equals
that order. Unconditional `DEL` is the classic distributed-lock bug: order A's hold lapses, order B
acquires the seat, then A's late release deletes B's lock and the seat is silently double-sold.

**Both script bodies are left for the developer to write** — see R11.

---

## R3 — The key is derived from the show, never from message identity

**Decision**: `seat:{showId}:{seatId}`.

**Rationale**: The project brief predates step 1's contract freeze, where the field naming a concert
was renamed from `eventId` to `showId` precisely because one word was serving as both the concert and
the message's own identity (step 1 FR-003). Carrying the brief's spelling forward would key holds by
message identity, which is *unique per delivery*. A redelivered request would then contend with
nothing, take a second hold on a seat it already holds, and the mutual exclusion the whole feature
exists to provide would be silently absent while every test that does not redeliver still passes.

`common-events` makes this checkable rather than a matter of care: `OrderCreated` exposes `showId()`
and `messageId()` as separate accessors of the same type, so the key builder names the field it means.

---

## R4 — What is authoritative, and how the fast store is rebuilt

**Decision**: PostgreSQL reservations are authoritative. Redis is a cache of them. On startup the
service replays every `HELD` reservation whose hold has not lapsed into Redis, using `SET key orderId
PXAT <lock_expires_at_epoch_millis>`, and only then starts the Kafka listener.

**Rationale**: The environment already forces this. `infra/docker-compose.yml` runs Redis with
`--save ""` — snapshotting deliberately off — so a restart loses every hold. Without a rebuild, the
first booking after a restart is judged against a store that has forgotten all existing holds, which
is a double-booking with no error anywhere. The constitution's Architecture constraint requires shared
inventory to have one authoritative source; this is that decision made concrete.

`PXAT` sets an *absolute* expiry rather than a fresh duration, which is what satisfies FR-016: a
restored hold lapses when its reservation says it lapses, not a full 120 seconds after the restart.
Using `EX 120` here would silently extend every in-flight hold on every restart, and the step-4 fencing
check would then trust a hold the rest of the system believes has already lapsed.

**Ordering the rebuild before consumption**: `spring.kafka.listener.auto-startup: false`, then an
`ApplicationRunner` performs the rebuild and calls `KafkaListenerEndpointRegistry#start()`. Rejected
`@PostConstruct` (runs before the datasource is guaranteed ready in all orderings) and
`ApplicationReadyEvent` alone (fires *after* listeners have already started, which is exactly backwards).

---

## R5 — Expressing "no two live reservations hold one seat" as a database constraint

**Decision**: `reservation_seats` carries `show_id`, `seat_label`, and a nullable `released_at`, with

```sql
CREATE UNIQUE INDEX ux_reservation_seat_live
    ON reservation_seats (show_id, seat_label)
    WHERE released_at IS NULL;
```

**Rationale**: This is the guarantee that survives Redis being wrong. If the Lua script is ever
mis-written, the database still refuses to record the second claim, so the failure is a rejected
booking rather than a double-sold seat.

The index must key on something the child row owns. A partial index cannot reference the parent's
`status` column, and it cannot use `lock_expires_at > now()` either — PostgreSQL requires index
predicates to be immutable, and `now()` is not. That constraint is the concrete reason FR-017's
explicit expired state exists: liveness has to be a stored fact, not a comparison performed at read
time.

`released_at IS NULL` is deliberately **not** a duplicate of the parent's status. It answers a
different question — *is this seat claimed?* — which is true for both `HELD` and `COMMITTED`, and false
for `EXPIRED` and `RELEASED`. The mapping is one-way and total, and it is maintained in exactly one
service method, following the same guarded-redundancy discipline step 2 used for `outbox.status`
alongside `published_at`.

**Alternatives considered**: denormalising the parent `status` onto every seat row (two copies of the
same fact, free to drift); deleting seat rows on release (loses the audit record FR-021 requires);
one flat `seat_holds` table with no parent (makes `@Version` per-seat and dissolves the reservation
aggregate the contract's `reservationId` names).

---

## R6 — Retiring a lapsed reservation without depending on a sweeper

**Decision**: A booking that contends for seats whose previous reservation has lapsed retires that
reservation — sets `released_at` on its seat rows and moves it to `EXPIRED` — in the **same
transaction** as its own insert. A `@Scheduled` sweeper does the same for seats nobody asks for again.

**Rationale**: Without inline retirement the design has a hole that only appears under load. Redis
frees a seat the instant its TTL lapses, so a new booking can legitimately win the Redis hold while the
old reservation is still `HELD` in Postgres — and the unique index of R5 then rejects a booking the
system just granted. Correctness would depend on a background job having run recently enough, which is
not a property anyone can test reliably.

Making retirement part of the winning booking's own transaction means the sweeper can be late,
stopped, or absent without a single booking failing. It exists only so that seats nobody contends for
again do not accumulate as live-looking rows, and it is deliberately off the path a buyer waits behind.

---

## R7 — Idempotency: the table the brief got wrong twice

**Decision**: `processed_messages(message_id UUID, consumer_name VARCHAR(64), processed_at TIMESTAMPTZ,
PRIMARY KEY (message_id, consumer_name))`, inserted in the same transaction as the state change, with
`DataIntegrityViolationException` meaning "already handled".

**Rationale**: Two corrections to the brief's `processed_events(event_id UUID PRIMARY KEY,
consumer_name, processed_at)`.

*The key.* With `message_id` alone as the primary key, `consumer_name` is decoration. The first
consumer to handle a message locks every other consumer in the same database out of it — silently, as
a skip rather than an error. Nothing in step 3 breaks, because its two consumers read different
channels; the bug lands whenever a second consumer reads a channel a first one already reads, and it
presents as "one handler mysteriously never runs".

*The naming.* Step 1 removed the word "event" as a field name from the contract module because it was
ambiguous between the message and the concert. A column called `event_id` holding what the contracts
call `messageId` reintroduces exactly that ambiguity in the one table whose entire job is to identify
messages.

**Placement**: the guard runs **before** the Redis script (FR-032), so a redelivery never reaches
contention at all.

---

## R8 — Announcing the outcome: a second outbox, copied not abstracted

**Decision**: An `outbox` table and relay in this service, ported from `order-service` rather than
extracted into a shared module.

**Rationale**: The alternative — publish directly after commit — leaves the same gap the outbox exists
to close, arriving from the consumer side. A crash between committing the reservation and publishing
`SeatsReserved` leaves an order holding seats that nothing downstream knows about; the redelivery then
finds the message already processed and skips, and the saga stalls with no error anywhere. Recording
the announcement in the same transaction turns that into ordinary outstanding work.

It also decouples publication from consumption: a broker outage stops the relay, not the listener, so
one order's undeliverable outcome never back-pressures unrelated orders sharing its partition.

**On the duplication**: this is the second copy of the pattern. The constitution requires an
abstraction to be justified by demonstrated need, and two instances is the point at which the need
first becomes *visible*, not yet demonstrated. Extraction is revisited if a third service needs one —
recorded here so that decision is made deliberately rather than by accumulation.

The relay body is **not** re-stubbed: it was the step-2 exercise, it is implemented and tested, and
repeating it would teach nothing. The `TODO(me)` exercises for this step are the two Lua scripts.

---

## R9 — When a request cannot be decided at all

**Decision**: Spring Kafka's `DefaultErrorHandler` with an `ExponentialBackOff` bounded by
`outbox.consumer.max-attempts`, recovering to a `DeadLetterPublishingRecoverer`.

**Rationale**: None of the three frozen refusal causes means "we could not tell". Announcing one when
Redis is unreachable states something about the seats that was never established and permanently
cancels an order that would have succeeded. Failing the consume instead leaves the offset uncommitted,
so the message is redelivered and a transient outage self-heals.

**Two classes of failure, deliberately routed differently:**

| Failure | Retryable? | Mechanism |
|---|---|---|
| Redis or PostgreSQL unreachable, transaction timeout | Yes | backoff, then DLT at the attempt limit |
| Unknown `schemaVersion` (step 1 FR-023) | No | `addNotRetryableExceptions` → DLT immediately |
| Deserialization failure | No | `ErrorHandlingDeserializer` → DLT immediately |

**Gotcha, verified against the provisioning script**: `DeadLetterPublishingRecoverer` defaults to a
`-dlt` suffix, producing `order.created-dlt`. Step 1's `create-topics.sh` provisioned
`order.created.DLT`, and `Topics.dlt()` is the contract for that name. The destination resolver must
be overridden to use `Topics.dlt(topic)`, or messages land in an unprovisioned topic — and with
`auto.create.topics.enable=false` in the real environment, the recovery itself then fails.

**Accepted cost**: retrying holds up the partition, so unrelated orders behind the message wait. That
is honest back-pressure while a dependency is down, and it is precisely why the attempt count is
bounded rather than infinite.

---

## R10 — Where concurrency is actually exercised

**Decision**: Two test levels. Exact-count contention assertions call `ReservationService` directly
from a large thread pool against real Redis and real PostgreSQL. A separate end-to-end test drives a
smaller batch through Kafka.

**Rationale**: The `order.created` channel has three partitions, frozen in step 1, and a consumer
processes one partition's records sequentially. In-process concurrency through the channel is therefore
capped at three regardless of how many messages or instances are involved. A broken all-or-nothing
hold can survive a three-way race by luck, so a channel-driven test would produce weak evidence wearing
the costume of strong evidence — which is the specific failure the constitution's concurrency rule
exists to prevent.

Direct invocation reaches hundreds of genuinely simultaneous callers. It cannot, however, prove the
consumer wiring, the duplicate guard on the delivery path, the outbox and its relay, per-order
ordering, or the contracts on the wire — so the end-to-end test carries those. Neither alone is
sufficient.

**Fixtures**: each concurrency test provisions its own show and seat pool. SC-003 needs at least 500
distinct seats; the seeded plan carries about eleven. Seeding a large venue instead was rejected —
tests sharing one pool interfere unless each partitions it carefully, which makes failures
order-dependent, and it would ship data to every environment that exists only to be tested against.

**Latch discipline**: threads are released by a `CountDownLatch` after all are parked at the barrier,
so the test measures contention rather than thread-startup skew.

---

## R11 — The Lua bodies stay with the developer

**Decision**: `lock_seats.lua` and `release_seats.lua` ship as files containing only a header comment
stating their contract. Everything around them ships working: the `DefaultRedisScript<Long>` beans, the
calling service method, the schema, the seeding, and the tests that judge them.

**Rationale**: This follows CLAUDE.md's explicit `TODO(me)` marker and the pattern already established
for the outbox relay in step 2. The atomic check-then-set is the one piece of this step whose reasoning
is worth working out by hand rather than reading — it is the difference between a marketplace that
double-books and one that does not, and it is four lines long.

The tests are written first and fail, which is also how the constitution's "fail before, pass after"
rule is satisfied structurally rather than ceremonially.

---

## R12 — Schema isolation inside one PostgreSQL instance

**Decision**: inventory-service owns the `inventory` schema in the existing `marketplace` database, set
via `spring.flyway.schemas` / `default-schema` and `hibernate.default_schema`.

**Rationale**: `infra/docker-compose.yml` provisions one database and one role. Pointing a second
service's Flyway at the same schema would have two migration histories fighting over one
`flyway_schema_history` table, and the second service to start would fail its validation. A separate
schema gives each service its own history table and its own namespace with no compose change, no second
database, and no new credentials — which also keeps Constitution Principle V out of the way, since
nothing has to be provisioned.

**Connection budget, worth stating because it is tighter than it looks**: PostgreSQL is capped at
`max_connections=50`. order-service takes 20. inventory-service is set to **12**, leaving room for
payment-service and projection-service in steps 4 and 6 without revisiting the container. The test
suite already shrinks pools to 5 per context for the same reason.

---

## R13 — Metrics that distinguish the failure modes from each other

**Decision**: five meters.

| Meter | Type | Why it earns its place |
|---|---|---|
| `inventory.holds.granted` | counter | The denominator for everything else |
| `inventory.holds.refused` | counter, tagged `cause` | A service refusing everything and a service refusing nothing look identical without the tag |
| `inventory.decision.duration` | timer | Carries SC-004's p95 budget and SC-020's refusal-cost comparison |
| `inventory.outbox.oldest.pending.age` | gauge | Backlog *age*, not depth — depth spikes harmlessly during a burst, while a rising oldest age means the relay is losing ground |
| `inventory.messages.deadlettered` | counter | Otherwise a service failing to decide anything is indistinguishable from a service receiving nothing |

---

## R14 — What this step deliberately does not build

Recorded so the omissions read as decisions rather than gaps.

- **Releasing and committing holds.** `OrderCancelled` and `OrderConfirmed` have no publisher until
  steps 4 and 5. `release_seats.lua` and the `RELEASED`/`COMMITTED` states are scaffolded so those
  steps fill in a body rather than design a mechanism, but neither is wired to a consumer here.
- **Any HTTP surface beyond actuator.** Availability is the read model's job in step 6. A query
  endpoint here would create a second answer to the same question before the intended one exists.
- **Service registry registration.** No registry exists until step 7, matching order-service.
- **Hold extension or renewal.** A hold lives exactly its configured lifetime. Whether a saga that
  outlives its hold may proceed is step 4's fencing check, using the lapse moment reported here.
- **Outbox and processed-message retention.** Rows are kept indefinitely, matching the gap step 2
  recorded rather than silently ignored.
