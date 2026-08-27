# Phase 1 Data Model: Seat Holds & the Inventory Authority

**Feature**: `003-inventory-seat-locks` | **Date**: 2026-08-27

Six tables in the `inventory` schema (R12), plus one Redis key shape. Everything durable lives in
PostgreSQL; Redis holds only the fast, self-expiring claim.

---

## Entity relationship

```text
shows (1) ──< show_seats                 the seating plan: which labels exist
   │
   └──< reservations (1) ──< reservation_seats     what an order claimed

processed_messages     standalone — the delivery guard, keyed by (message, consumer)
outbox                 standalone — announcements awaiting the relay

Redis:  seat:{showId}:{seatId} → orderId,  TTL 120s
        a CACHE of reservation_seats where released_at IS NULL
```

The two stores answer different questions. Redis answers *is this seat claimed right now* fast enough
to arbitrate a thousand simultaneous contenders. PostgreSQL answers *what actually happened* and
survives a restart. When they disagree, PostgreSQL wins and Redis is rebuilt from it (R4).

---

## Table: `shows`

| Column | Type | Notes |
|---|---|---|
| `show_id` | `UUID` | PK. The value carried as `showId` on `OrderCreated` |
| `name` | `VARCHAR(200)` | `NOT NULL`. For diagnosis only; nothing branches on it |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT now()` |

Seeded by migration (FR-034). Two shows: one carrying exactly the ten seats the step-9 load test
contends over, and one further show so that per-show scoping of seat labels is exercisable — a seat
label valid in one show must be meaningless in the other.

A request naming a `show_id` absent from this table is refused with `SHOW_NOT_FOUND`.

---

## Table: `show_seats`

| Column | Type | Notes |
|---|---|---|
| `show_id` | `UUID` | PK part 1, FK → `shows` |
| `seat_label` | `VARCHAR(16)` | PK part 2 |

The composite primary key is the point: a seat label is meaningful only relative to its show, so `A1`
in one show and `A1` in another are two different seats and the key says so. It also makes the
existence check a single indexed lookup per seat.

A requested label absent from this table, for a show that *does* exist, is refused with
`SEATS_NOT_FOUND` — deliberately distinct from `SEATS_ALREADY_HELD`, because one never succeeds on
retry and the other very well might.

---

## Table: `reservations`

| Column | Type | Notes |
|---|---|---|
| `reservation_id` | `UUID` | PK. The value announced as `reservationId` on `SeatsReserved` |
| `order_id` | `UUID` | `NOT NULL UNIQUE`. Also the saga id |
| `show_id` | `UUID` | `NOT NULL`, FK → `shows` |
| `status` | `VARCHAR(16)` | `NOT NULL`, CHECK in `('HELD','EXPIRED','COMMITTED','RELEASED')` |
| `lock_expires_at` | `TIMESTAMPTZ` | `NOT NULL`. The moment announced as `lockExpiresAt` |
| `version` | `BIGINT` | `NOT NULL DEFAULT 0`. Hibernate `@Version` (FR-012) |
| `created_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT now()` |
| `updated_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT now()` |

`order_id UNIQUE` is a second line of defence behind the idempotency guard: even if the guard were
bypassed, one order cannot acquire two reservations.

Only reservations are recorded — a refusal writes no reservation row. Its durable trace is the
processed-message row and the outbox row announcing it.

### State machine

```text
                    ┌─────────────────────────► COMMITTED   (step 4: OrderConfirmed)
                    │
   (decide) ──► HELD┼─────────────────────────► RELEASED    (step 5: OrderCancelled)
                    │
                    └─────────────────────────► EXPIRED     (this step: hold lapsed)
```

Only `HELD` and `EXPIRED` are reachable in this step. `COMMITTED` and `RELEASED` are declared now so
steps 4 and 5 fill in a transition rather than migrate the enum, and so the CHECK constraint does not
have to be rewritten later.

`EXPIRED` exists for a concrete reason rather than for tidiness: it makes liveness a stored fact rather
than a comparison against the clock, which is what allows the constraint below to be written at all
(R5). PostgreSQL requires index predicates to be immutable, and `now()` is not.

**A reservation is never deleted** (FR-021). The record of why an order failed is what makes a stalled
saga diagnosable, and step 5's release path acts on a row that still exists.

---

## Table: `reservation_seats`

| Column | Type | Notes |
|---|---|---|
| `reservation_id` | `UUID` | PK part 1, FK → `reservations` |
| `seat_label` | `VARCHAR(16)` | PK part 2 |
| `show_id` | `UUID` | `NOT NULL`. Denormalised from the parent so the index below can exist |
| `released_at` | `TIMESTAMPTZ` | `NULL` while the seat is claimed |

```sql
CREATE UNIQUE INDEX ux_reservation_seat_live
    ON reservation_seats (show_id, seat_label)
    WHERE released_at IS NULL;
```

**This index is the guarantee that survives Redis being wrong.** If the Lua script is ever mis-written,
the database still refuses the second claim, so the failure mode is a rejected booking rather than a
double-sold seat. `LiveSeatConstraintIT` verifies it with Redis bypassed entirely — testing it through
the normal path would only prove Redis works.

**Why `released_at` is not a duplicate of the parent's `status`.** It answers a different question —
*is this seat claimed?* — which is true for both `HELD` and `COMMITTED`, and false for `EXPIRED` and
`RELEASED`. The mapping is one-way and total:

| Parent `status` | `released_at` | Seat is |
|---|---|---|
| `HELD` | `NULL` | claimed, temporarily |
| `COMMITTED` | `NULL` | claimed, permanently |
| `EXPIRED` | set | free |
| `RELEASED` | set | free |

This is the same guarded-redundancy discipline step 2 used for `outbox.status` alongside
`published_at`, and it is maintained in exactly one service method so there is one place to verify it.

**Why `show_id` is denormalised here.** A partial unique index cannot reference a joined table, and the
uniqueness being enforced is *per show*, not per reservation. The column is written once at insert and
never updated, so it cannot drift from its parent.

---

## Table: `processed_messages`

| Column | Type | Notes |
|---|---|---|
| `message_id` | `UUID` | PK part 1. The contract's `messageId` — never the show |
| `consumer_name` | `VARCHAR(64)` | PK part 2 |
| `processed_at` | `TIMESTAMPTZ` | `NOT NULL DEFAULT now()` |

Two deliberate deviations from the original brief's
`processed_events(event_id UUID PRIMARY KEY, consumer_name, processed_at)` — both recorded in R7:

**The key is composite.** With message identity alone as the primary key, `consumer_name` is
decoration: the first consumer to handle a message locks every other consumer in the same database out
of it, silently, as a skip rather than an error. Nothing in step 3 breaks — its consumers read
different channels — but the bug lands the moment a second consumer reads a channel a first one already
reads, and it presents as "one handler mysteriously never runs".

**The naming follows step 1.** The contract module removed the word "event" as a field name because it
was ambiguous between the message and the concert (step 1 FR-003). A column called `event_id` holding
what the contracts call `messageId` reintroduces exactly that ambiguity, in the one table whose entire
job is to identify messages.

The insert happens in the same transaction as the state change (FR-028) and **before** the Redis script
runs (FR-032), so a redelivered request never contends with the hold it already owns.

---

## Table: `outbox`

Identical in shape to order-service's, ported rather than abstracted (R8): `id`, `aggregate_id`,
`event_type`, `payload jsonb`, `traceparent`, `tracestate`, `status`, `attempts`, `last_error`,
`created_at`, `published_at`, with the two CHECK constraints and the two partial indexes.

`aggregate_id` is the order id, so the partition key preserves per-order ordering (FR-024).
`event_type` is `Topics.SEATS_RESERVED` or `Topics.SEATS_REJECTED` from `common-events` — never a
literal string, so a publisher cannot invent a channel name no consumer subscribes to.

---

## Redis: the seat hold

```text
key    seat:{showId}:{seatId}
value  orderId
TTL    120 s   (absolute PXAT when restored on startup — R4)
```

**The key names the show, never the message** (R3, FR-007). The original brief's `seat:{eventId}:...`
predates step 1's rename, where `eventId` was split into `showId` and `messageId`. A hold keyed by
message identity is unique per delivery, so a redelivered request would contend with nothing and take a
second hold on a seat it already holds — and the mutual exclusion this whole feature provides would be
silently absent while every test that does not redeliver still passes. `SeatKeyTest` asserts the key
builder reads `showId()`.

**The value is the owner**, which is what makes release safe: `release_seats.lua` deletes only keys
whose value matches the releasing order. An unconditional `DEL` is the classic lock bug — order A's
hold lapses, order B acquires the seat, A's late release deletes B's lock, and the seat is silently
double-sold.

**TTL is what makes an abandoned saga self-healing** (FR-008). No process is responsible for expiry, so
there is no process whose failure strands inventory.

---

## Validation rules

| Rule | Where enforced | Requirement |
|---|---|---|
| Show must exist | `shows` lookup → `SHOW_NOT_FOUND` | FR-023, FR-033 |
| Every seat label must exist in that show | `show_seats` lookup → `SEATS_NOT_FOUND` | FR-023, FR-033 |
| All seats free, or none taken | `lock_seats.lua`, atomically | FR-004, FR-005 |
| No two live reservations on one seat | `ux_reservation_seat_live` | FR-020 |
| One reservation per order | `reservations.order_id UNIQUE` | FR-010 |
| One effect per (message, consumer) | `processed_messages` PK | FR-028, FR-029 |
| Losing writer detected | `@Version`, retried once then surfaced | FR-012, FR-013 |
| `lockExpiresAt` strictly after `occurredAt` | built together in `OutboxWriter` | FR-009 |

---

## Mapping to the frozen contracts

Both messages are step-1 contracts and are published unchanged.

### `SeatsReserved`

| Contract field | Source |
|---|---|
| `messageId` | freshly generated per message |
| `sagaId` | `reservations.order_id` |
| `occurredAt` | the decision's own timestamp |
| `schemaVersion` | `1` |
| `orderId` | `reservations.order_id` |
| `seatIds` | `reservation_seats.seat_label`, sorted for determinism |
| `reservationId` | `reservations.reservation_id` |
| `lockExpiresAt` | `reservations.lock_expires_at` |

**`occurredAt` and `lockExpiresAt` must be computed from one instant**, in the same expression that
builds the message. The contract's compact constructor requires the lapse to fall *strictly* after
`occurredAt` and throws otherwise — so deriving `occurredAt` at publish time while `lockExpiresAt` came
from lock-acquisition time produces a row whose message can never be constructed, and the outbox row is
unpublishable forever. Building both at write time makes that unrepresentable.

### `SeatsRejected`

| Contract field | Source |
|---|---|
| `messageId` | freshly generated per message |
| `sagaId` / `orderId` | the refused order |
| `occurredAt` | the decision's own timestamp |
| `schemaVersion` | `1` |
| `seatIds` | **the full requested set**, not only the unavailable ones — the request was refused as a unit |
| `reason` | `RejectionReason.SEATS_ALREADY_HELD` \| `SEATS_NOT_FOUND` \| `SHOW_NOT_FOUND` |

There is no fourth cause, and none of the three means *"the decision could not be made"*. A request that
could not be judged produces **no** message at all and is redelivered instead (FR-047, R9).
`OutcomeMappingTest` asserts the mapping is total: every reachable outcome maps to exactly one message,
and no outcome maps to a cause that does not describe it.
