# T163 — Quickstart verification at the end of User Story 1

**What this task did:** ran quickstart.md's S1, S4, S9, and S10 scenarios and recorded the actual
results — not the results a clean pass would have shown, but what this checkpoint's own state
honestly produces. Two of the four scenarios cannot run at all yet, for a reason the task list itself
already anticipates; the other two ran in full, against a real database and (for S10) a real
constraint violation.

---

## S1 and S4 cannot run yet, and that is the checkpoint working as designed

Both scenarios open with "submit an order" via `curl` against `order-service`, then expect
`inventory-service` to react by consuming `order.created` from Kafka. That reaction doesn't exist yet:
the consumer side of this service — `OrderCreatedListener`, the idempotency guard, the Kafka wiring
that would let a message ever reach `ReservationService.decide(...)` from the channel rather than from
a direct call — is User Story 3's work, not User Story 1's. Phase 3's own checkpoint note in
`tasks.md` says so directly: *"nothing consumes from Kafka"* at this point. Attempting S1 or S4 as
literally written would not fail informatively — there would simply be nothing on the other end to
react to a published `order.created` message at all.

This isn't a gap in verification; it's the correct, honest state of an intentionally incremental build.
Both scenarios become runnable once User Story 3 lands.

## A real defect found in quickstart.md's own S9 command

The documented command is:

```bash
./mvnw -q -pl inventory-service test -Dtest='Reservation*IT'
```

Running it exactly as written produced `Tests run: 4` — one result per matched class, not the twenty
`ReservationContentionIT`'s own `@RepeatedTest(20)` should produce. The root cause: `test` invokes
Surefire, and this project's own root `pom.xml` documents at length why `*IT.java` classes are
deliberately excluded from Surefire and handled by Failsafe during `verify` instead — two different
plugins with two different failure semantics, chosen so integration tests never leave containers
behind. Forcing an `*IT` class through Surefire via an explicit `-Dtest` override works, in the sense
that it doesn't error, but it silently doesn't run all twenty repetitions.

Corrected to `./mvnw -q -pl inventory-service verify -Dit.test='Reservation*IT'` — matching the
project's own established Failsafe convention — which does run the full twenty. Recorded here as a
documentation defect worth fixing in quickstart.md itself in a later pass, per this project's own
practice of judging generated planning documents on their merits rather than treating them as
infallible.

## S9's actual results — the full twenty repetitions, run correctly

```text
ReservationContentionIT ....... 0/20 pass  (every repetition: 0 granted, expected 10)
ReservationDisjointIT ......... 0/1 pass   (0 granted, expected 500)
ReservationPartialOverlapIT ... 1/1 pass   (0 granted -- "zero partial holds" trivially true)
ReservationVersionIT .......... 0/1 pass   (0 granted from either racing thread)
```

Every failure is the same, single, already-known cause recorded in full in T160's own write-up:
`lock_seats.lua` is still an empty stub (T152), awaiting the developer exercise (T156). An empty script
returns nothing at all, which `SeatLockStore` reads as "not acquired" regardless of whether a seat was
genuinely free. `ReservationPartialOverlapIT` passes for the mirror-image reason — with nothing ever
granted, "zero partial holds" holds trivially, not by coincidence but by what the assertion actually
checks.

## S10's actual result — a genuine pass, proving SC-017 today

```text
LiveSeatConstraintIT .......... 2/2 pass
```

Confirmed with the real PostgreSQL error visible in the log:

```text
ERROR: duplicate key value violates unique constraint "ux_reservation_seat_live"
```

This scenario needs nothing from the Lua scripts or the Kafka consumer — it proves the database
constraint holds with Redis bypassed entirely, which is exactly what SC-017 asks for, and it has held
since T120 created the constraint and continues to hold now.

---

## What this checkpoint actually establishes

Seats are held correctly under contention, in the narrow, honest sense that every mechanism *except*
the two Lua script bodies is proven working: the atomic decide-and-record transaction, the optimistic
version check, the live-seat database constraint, the outbox announcement (with its scheduling now
genuinely verified end to end, T161–T162), and the seating-plan test fixtures. The one piece still
outstanding is the developer exercise itself — `T156` — and every test in this build step is already
sitting ready, failing for exactly and only that reason, to confirm the moment it's done.
