# T147 — `LiveSeatConstraintIT`: the one test in this batch written to pass today

**What this task did:** wrote `LiveSeatConstraintIT`, proving SC-017 — that two live reservations can
never cover one seat of one show, enforced by the database itself, with Redis completely bypassed.

Every other test in this batch (T142–T146, T148–T150) was written against a class that doesn't exist
yet, and is confirmed to fail to compile for exactly that reason. This one is different, and worth
calling out as different rather than letting it blend in: it needs nothing beyond `Reservation` and
`ReservationSeat` (T126, T127), both already built — so it was written to actually pass, today, and
was verified doing exactly that.

---

## Why this test could be written now when the others couldn't

SC-017's entire claim is about the database alone: `ux_reservation_seat_live`, the partial unique
index from `V2__create_reservations.sql`, either rejects a second live claim on one seat or it doesn't,
and that is a property of the schema and the two JPA entities that map onto it — nothing about
`ReservationService`, Redis, or the Lua scripts enters into it. Testing through the normal path would
only prove Redis works, which is precisely backwards from what this guarantee is *for*: it exists as
the thing that still holds even if `lock_seats.lua` — the one piece of this service left for a human to
write by hand — turns out to be wrong.

## Verified for real, not merely reasoned about

Since every other file in this batch currently fails to compile, the whole module's test suite cannot
run as a normal `mvn verify` invocation — Maven fails the entire compilation unit the moment any file
in it has an unresolved symbol, so nothing gets to the point of executing tests at all right now, this
file included. Rather than leave that as an assumption, the other eight files were moved aside
temporarily, this one run directly against the real stack, and the files restored afterward — the same
methodology order-service's own `T089` doc describes using to confirm a failure is attributable to
exactly one file.

Both tests passed. The first, forcing the actual violation, produced this in the log — the real
PostgreSQL error, not a simulated one:

```text
ERROR: duplicate key value violates unique constraint "ux_reservation_seat_live"
Detail: Key (show_id, seat_label)=(272d4638-8515-4995-8d83-6e614ccf0910, seat-0) already exists.
```

The second test — releasing the first claim and confirming a genuinely new one is accepted
immediately afterward — also passed, closing the loop on the opposite-direction concern: the index
must not simply forbid a seat forever the moment anything has ever touched it.

## What each test actually attempts, and why the second one exists at all

The first test persists two `Reservation`/`ReservationSeat` pairs naming the identical `(showId,
seatLabel)` while both are live (`releasedAt` still null on both), and asserts the second `flush()`
throws with the constraint's own name in the stack trace — confirming the *database*, not some
incidental Hibernate-side check, is what refused it.

The second test exists because a constraint that merely blocks forever would be its own kind of bug —
indistinguishable from a working one in a test that only ever tries once. Releasing the first claim
(`released_at` set, status moved to `EXPIRED`) and then successfully persisting a genuinely new live
claim on the same seat is what proves the index enforces liveness specifically, the way
`data-model.md` describes it, rather than uniqueness on the seat forever.
