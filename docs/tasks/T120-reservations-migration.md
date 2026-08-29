# T120 — The reservations migration

**What this task did:** wrote `V2__create_reservations.sql`. It creates `reservations` (the durable
record that an order holds a set of seats) and `reservation_seats` (the individual seats one
reservation claims), and adds `ux_reservation_seat_live` — a single database index that is, on its
own, the reason this service can be trusted even if the rest of it is buggy.

This is the most important file in this build step. It is worth reading slowly.

---

## Two stores, one truth

This service has two places that know something about seat availability: Redis and PostgreSQL. They
are not equals. Redis exists because it is fast — it can arbitrate a thousand simultaneous booking
attempts against ten seats in the time a database transaction would still be queuing. But speed and
durability are usually a trade, and this project makes that trade explicit: this environment runs
Redis with `--save ""`, meaning snapshotting is switched off entirely. **Every restart of the Redis
container throws away every hold it currently knows about.**

If Redis were the only record of who holds what, a Redis restart would be indistinguishable from every
seat suddenly becoming free — including seats genuinely still claimed by a buyer mid-checkout.
PostgreSQL is what survives that. It is the *authoritative* record, and Redis is downgraded to being a
cache of it — one that gets rebuilt from PostgreSQL every time the service starts (that rebuild is
`SeatLockRebuilder`, arriving in T179). This migration creates the tables that make that authority
real.

---

## `reservations`: one row per successful hold

```sql
CREATE TABLE reservations (
    reservation_id    UUID          PRIMARY KEY,
    order_id          UUID          NOT NULL UNIQUE,
    show_id           UUID          NOT NULL REFERENCES shows (show_id),
    status            VARCHAR(16)   NOT NULL,
    lock_expires_at   TIMESTAMPTZ   NOT NULL,
    version           BIGINT        NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT reservations_status_known
        CHECK (status IN ('HELD', 'EXPIRED', 'COMMITTED', 'RELEASED'))
);
```

A few things worth calling out one at a time.

**`order_id` is `UNIQUE`, and that's a second safety net, not the main one.** The idempotency guard
arriving in later tasks (`processed_messages`, T121) is what's *supposed* to stop a redelivered message
from creating a second reservation. But "supposed to" is doing work in that sentence — code has bugs.
This constraint means that even if the guard were somehow bypassed, the database itself would refuse a
second reservation for the same order. Defense in depth, cheaply bought.

**Only two of the four statuses are reachable right now.** The `CHECK` constraint admits `HELD`,
`EXPIRED`, `COMMITTED`, and `RELEASED`, but this build step can only ever produce the first two — an
order gets its seats held, and eventually either something confirms them (`COMMITTED`, arriving in
step 4) or the hold simply expires with nothing having happened (`EXPIRED`, reachable right now). Why
declare all four now instead of just the two that apply? Because adding a new value to a `CHECK`
constraint later means an actual migration against a live table, while declaring it now costs nothing.
The alternative is that the first `COMMITTED` reservation in step 4 fails this constraint at exactly
the worst moment — in production-shaped code, during the first real use of a feature.

**No `orders` foreign key.** `order-service` owns the `orders` table, in its own schema, in its own
service. This service never reaches across that boundary — not because it's technically impossible
(it's the same PostgreSQL instance), but because a foreign key would make writes here fail if
order-service's schema were ever unreachable, which is exactly the coupling a message-driven saga is
built to avoid. The two services agree about an order only by exchanging messages, never by sharing a
table.

**Reservations are never deleted.** There is no `DELETE` statement anywhere touching this table, in
this migration or in any code arriving later. If a reservation expires, its *row* records that fact —
it doesn't vanish. That's what makes a stalled saga diagnosable months later: the evidence of what
happened to an order's seats is still sitting in the database, not gone.

---

## `reservation_seats`: why one seat gets its own row

```sql
CREATE TABLE reservation_seats (
    reservation_id  UUID         NOT NULL REFERENCES reservations (reservation_id),
    seat_label      VARCHAR(16)  NOT NULL,
    show_id         UUID         NOT NULL,
    released_at     TIMESTAMPTZ,
    PRIMARY KEY (reservation_id, seat_label)
);
```

A booking can claim several seats at once, and those seats need their own rows rather than, say, an
array column on `reservations`, for the same reason `order-service` gives each order's seats their own
table: a composite primary key — `(reservation_id, seat_label)` here — makes a *duplicate* seat on one
reservation structurally impossible, not merely something application code has been told not to allow.

Two columns are worth a closer look.

**`show_id` is copied down from the parent reservation, and that repetition is deliberate rather than
sloppy.** The index described below can only be built on columns that live directly on this table —
PostgreSQL cannot build a partial index that reaches into a joined table to evaluate its condition. And
the uniqueness this index enforces genuinely is *per show* — seat "A1" needs to be unique within a
show, not globally. So `show_id` has to be right here, even though it's also reachable by joining up to
`reservations`. It's written once, at insert time, and nothing ever updates it afterward — so there's
no window in which it could drift out of sync with its parent.

**`released_at` is not simply a copy of the parent's status, even though it looks like it should be.**
It answers a narrower, more specific question: *is this particular seat claimed right now?* That
question has the same answer (`NULL`, meaning "yes, claimed") for two different parent statuses —
`HELD` and `COMMITTED` — and a different answer (a real timestamp, meaning "no, free") for the other
two — `EXPIRED` and `RELEASED`. It's a projection of the parent's state, computed by exactly one method
in the service layer, rather than an independent fact that could accidentally drift from the truth.

---

## The index that makes the rest of the system forgivable

```sql
CREATE UNIQUE INDEX ux_reservation_seat_live
    ON reservation_seats (show_id, seat_label)
    WHERE released_at IS NULL;
```

Here is the situation this index exists for. Somewhere in this service, a human being — not an AI, by
this project's own design (see `research.md` R11 and the `lock_seats.lua` stub arriving later) — is
going to write four lines of Lua by hand. Four lines is not a lot of code, but it's exactly the kind of
small, easy-to-get-subtly-wrong logic that decides whether a seat gets sold to one buyer or two. If
that Lua script has a bug, Redis will confidently tell two different orders that they both hold seat
A1.

This index is what stops that mistake from actually costing anyone a double-booked seat. It's a
`UNIQUE INDEX`, but with a `WHERE` clause — a **partial** index, meaning PostgreSQL only enforces
uniqueness among rows where `released_at IS NULL` (that is, seats currently claimed). Try to insert a
second live claim on `('some-show', 'A1')` while one already exists, and PostgreSQL itself throws a
`unique_violation` — no application code has to remember to check. If the Lua script above it is
wrong, the failure mode becomes "this booking gets rejected," which is a bad afternoon. Without this
index, the failure mode would be "this seat gets sold twice," which is the actual thing a ticket
marketplace exists to prevent.

**Why the condition is `released_at IS NULL` and not something more obviously about time — like
"hasn't expired yet"?** PostgreSQL requires a partial index's condition to be *immutable*: given the
same row, it always has to evaluate to the same answer, forever. A condition like `now() <
lock_expires_at` fails that requirement outright — its answer depends on the wall clock, not on
anything actually stored in the row, so PostgreSQL refuses to build the index at all.

That restriction is not a workaround-worthy annoyance; it's the actual reason `reservations` has an
explicit `EXPIRED` status instead of leaving expiry as something inferred by comparing a timestamp to
`now()` on every read. Whether a reservation is *live* has to be a fact stored directly in the row —
something application code updates the instant it becomes true — rather than a calculation repeated by
every single piece of code that ever reads this table. Once liveness is a stored fact, `released_at IS
NULL` can express it, and the constraint above becomes possible to write at all.

---

## Verifying it — not by reading, by trying to break it

SQL that merely runs without error proves very little; the interesting claims here are about what the
database *refuses*. Both migrations were applied to a real PostgreSQL 16 instance and then deliberately
attacked:

**The live-seat index actually stops a double-booking:**
```text
NOTICE:  OK: unique index correctly rejected the second live claim on A1
NOTICE:  OK: after release, a new live claim on A1 succeeds
```
Two reservations for the same show both tried to claim seat A1 while both were live — the second
insert failed with `unique_violation`, exactly as required. Then the first reservation's claim on A1
was released (`released_at` set, status moved to `EXPIRED`) and the second reservation successfully
claimed A1 immediately afterward — proving the index doesn't just block everything indiscriminately;
it enforces liveness specifically, in both directions.

**The status `CHECK` constraint actually rejects garbage:**
```text
ERROR:  new row for relation "reservations" violates check constraint "reservations_status_known"
```
An attempted insert with `status = 'BOGUS'` was rejected, confirming the constraint is live and not
just present in the file.

**`order_id UNIQUE` actually rejects a second reservation for one order:**
```text
NOTICE:  OK: order_id UNIQUE rejected the second reservation
```

All of this was run in a scratch `inventory` schema against the project's own PostgreSQL container,
then torn down — nothing here left residue in the shared development database. The real Flyway history
for this service starts once the application boots against these files, in a later task.
