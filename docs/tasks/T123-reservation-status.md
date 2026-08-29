# T123 — `ReservationStatus`

**What this task did:** wrote the enum that names where a reservation sits in its lifecycle — `HELD`,
`EXPIRED`, `COMMITTED`, `RELEASED`. It's a one-file, no-logic task, and its interest is entirely in
*why* two of those four names can't be used yet and get declared anyway.

---

## An enum that describes a system that doesn't fully exist yet

This build step can only ever produce two of these four values. A booking succeeds and the
reservation becomes `HELD`. If nothing confirms the order before the hold's 120 seconds run out, it
becomes `EXPIRED`. That's the entire lifecycle this service can drive on its own.

`COMMITTED` — the state a reservation reaches once `order-service` confirms the order — doesn't exist
yet, because nothing publishes the message that would trigger it until step 4. `RELEASED` — a
reservation deliberately let go because the order was cancelled — is the same story, arriving in step
5.

So why write all four now, instead of the two that actually apply?

Look at `V2__create_reservations.sql` (T120): the `CHECK` constraint on the `status` column already
lists all four names. That constraint was written now on purpose, for the same reason `order-service`'s
`orders_status_known` constraint lists `CONFIRMED` and `CANCELLED` before either is reachable — adding
a new value to a live `CHECK` constraint is an actual migration against an actual table, while
declaring it up front costs nothing. If this enum admitted only `HELD` and `EXPIRED`, the day step 4
tries to move a reservation to `COMMITTED`, it would fail — not because anything is broken, but because
the code and the schema were never told the value existed. That's exactly the kind of failure that
shows up at the worst possible moment: the first time a real feature actually needs the thing that was
left out.

Storing the name rather than the ordinal position is also deliberate, matching every other status enum
in this project. An ordinal is just "the third thing in this list" — compact, but silently wrong the
day someone inserts a new constant in the middle of the list, because every row already written then
means something different with zero errors raised anywhere to notice.

---

## Why liveness isn't just "is the status HELD or COMMITTED"

This is the one place this enum's own doc comment says something worth expanding on. It would be
natural to assume "is this seat still claimed?" is simply a question about this enum — check if status
is `HELD` or `COMMITTED`, and you have your answer. That's even *true*, semantically. But it deliberately
isn't how the actual liveness check works.

The reason lives one file away, in `ReservationSeat` (T127): whether a specific seat is claimed is
tracked by its own `releasedAt` column, set to `NULL` while claimed and to a real timestamp once it
isn't — not by looking at the parent reservation's status at read time. The two facts happen to agree
(when the status is a live one, `releasedAt` is null; when it isn't, `releasedAt` is set), but they're
maintained as two separate pieces of state, deliberately kept in sync by exactly one method rather than
one being derived from the other via a live comparison.

The concrete reason for that split, spelled out fully in `V2__create_reservations.sql`'s own comments:
PostgreSQL's partial unique index — the guarantee that survives a bug in the Lua script — needs a
condition that never changes for a given row. A condition based on comparing this enum's value, or
comparing a timestamp to `now()`, both fail that requirement in different ways. `releasedAt IS NULL` is
a stored fact about the row itself, and that's what makes the index expressible at all.

---

## Verifying it

This is a pure enum with no behavior to exercise yet — nothing in this task reads or writes a database.
It compiles as part of the module (confirmed by a full build), and its actual correctness claim — that
it admits exactly the four values `V2__create_reservations.sql`'s `CHECK` constraint expects, no more
and no fewer — is the kind of thing that gets caught the moment `Reservation` (T126) tries to persist a
value and Hibernate's schema validation runs against the real table.
