# T156 — writing `lock_seats.lua` and `release_seats.lua`, and what turning them on for real found

**What this did:** filled in the two Lua script bodies that had been left as empty stubs since T152,
following the contract in `contracts/seat-lock-scripts.md` and the guide written for this task in T155.
Both files went from "a comment describing what must happen" to "code that actually does it" — and
because this is the first time in the whole build step that a booking attempt could genuinely succeed,
turning it on also exposed one real bug elsewhere that had been invisible until now.

---

## What "atomic" means here, in one sentence

Redis runs a Lua script as a single, uninterruptible step — no other command from any other client can
run in the middle of it. That single fact is the whole reason a script can safely do "check every seat,
then take every seat" as one indivisible action, when the same logic written as several separate Redis
calls from Java would leave a gap for two buyers to both see a seat as free at once.

## `lock_seats.lua`

The body does exactly two passes over the requested seats, never one:

1. **Check pass** — look at every key first, without changing anything. A key counts as available if
   it doesn't exist, or if it's already held by the *same* order asking now (that second case matters:
   without it, a retry after some unrelated failure would find the seats it just took a moment ago and
   refuse itself). If any key is held by someone else, stop immediately and change nothing.
2. **Set pass** — only reached if every key passed the check. Now, and only now, actually claim every
   key, each with the seat hold's TTL.

Splitting this into two passes is the entire point. A version that checked and set one seat at a time
in a single loop would, on finding the third of four seats already taken, have already claimed the
first two — and those two would stay claimed, by an order that was just refused, for the full two
minutes of the hold's lifetime. Nobody would own them and nobody could book them either.

## `release_seats.lua`

Deletes a key only if its current value still equals the order id asking to release it. A key already
gone, or a key now held by a different order, is left alone. The comparison is what stops the
textbook distributed-lock bug this script exists to prevent: order A's hold lapses on its own after two
minutes, order B legitimately takes the same seat, and then order A's now-stale cancellation message
finally arrives and tries to release it — an unconditional delete here would destroy B's hold, not A's,
and the seat would then look free to a third buyer while B still believes they hold it. Checking
ownership before deleting means A's late release simply does nothing, because the key no longer says
"A" by the time A's message shows up.

## Verifying it — the direct evidence, not just "the code looks right"

```text
Tests run: 9, Failures: 0, Errors: 0 -- SeatLockScriptIT
```

All nine guarantees `contracts/seat-lock-scripts.md` states for these two files pass: every-seat-free
succeeds, any-seat-held refuses and touches nothing, a retry doesn't refuse its own seats, every
claimed key carries the TTL, an unrequested seat is never touched, releasing deletes only the caller's
own keys, releasing never steals another order's key, releasing an already-gone key isn't an error, and
the deleted count is reported correctly.

## The bug this uncovered — not in the Lua, but next to it

Before this task, `SeatLockStore.tryLock(...)` always returned `false`, because an empty script returns
nothing at all. That meant `ReservationService.recordReservation(...)` — the method that actually writes
a new reservation to the database — had **never once run** in this whole build step, for any test, no
matter how the test was written. The moment the real scripts started returning `1` for a genuinely free
seat, that method ran for the first time, and one test that exists specifically to exercise it —
`LapsedRebookingIT`, proving a lapsed hold gets successfully rebooked — immediately failed with:

```text
ERROR: duplicate key value violates unique constraint "ux_reservation_seat_live"
```

The cause has nothing to do with Redis or the seat lock: `ReservationService.decide(...)` first retires
the old, lapsed reservation (marking its seat row as released) and then, in the same database
transaction, writes the new reservation's seat row. Both changes go through Hibernate, and Hibernate
does not send its database statements to PostgreSQL in the order the Java code made them — by default
it batches every pending *insert* ahead of every pending *update*, regardless of which was decided
first. The new seat row is an insert; releasing the old one is an update on an already-loaded row. Left
to Hibernate's own ordering, the new insert reached the database before the old release did — so, for
one brief moment as far as the database's own constraint was concerned, the same seat looked claimed
twice, and the database correctly refused it.

The fix is one line: force the release to actually reach the database (`.flush()`) before the new seat
row is ever written, removing the two statements from competing over which one Hibernate happens to
send first.

This is exactly the kind of bug the empty stub was hiding — not because the stub was wrong to be empty
(that was always the intended state for this step), but because a code path nobody can ever reach
teaches nothing about whether it's correct. The first time it *could* run was the first time it had a
chance to be checked.

## Result after both the scripts and the fix

```text
Tests run: 45, Failures: 0, Errors: 0  (mvn clean verify, whole module)
```
