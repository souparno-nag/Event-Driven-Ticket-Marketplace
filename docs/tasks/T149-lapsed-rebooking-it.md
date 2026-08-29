# T149 — Specifying SC-016, before `ReservationService` exists

**What this task did:** wrote `LapsedRebookingIT` — with the periodic sweeper disabled, a seat whose
previous hold has lapsed is rebooked successfully on the first attempt — against `ReservationService`
and `ReservationOutcome`, both still unwritten (T158, T160). Confirmed to fail via the compiler for
exactly that reason.

---

## Why disabling a sweeper that doesn't exist yet is the correct thing to write

`LapsedReservationSweeper` (T161) hasn't been written either, so
`inventory.sweeper.enabled=false` currently has no class reading it at all — setting it is, right now,
a no-op. It's set anyway, deliberately, rather than left for whoever writes T161 to remember to add
later. The reason is the whole point of this test: research.md R6 and FR-018 are explicit that
correctness must never depend on a background sweeper having run recently, and the only way to *prove*
that is to disable the thing being depended on and show nothing breaks. A version of this test that
added the property only after the sweeper existed would, for a while, be indistinguishable from a test
that never checked this at all — passing regardless of whether the sweeper secretly still mattered,
because it was still running. Writing the property in now means the moment `LapsedReservationSweeper`
exists, this test already carries the one setting that makes it a meaningful check rather than an
accidental one.

## A single call, deliberately — this is not a concurrency test

Every other reservation-behavior test in this batch races multiple threads against each other.
This one doesn't, on purpose: SC-016 is about whether inline retirement *works at all* on its own,
independent of whether it wins a race against a rival attempt. Adding concurrency here would blur two
separate questions together — "does retiring a lapsed reservation succeed" and "who wins when two
attempts collide," the second of which is `ReservationVersionIT`'s (T148) job specifically. Keeping
this one single-threaded is what makes a future failure here unambiguous: if this fails, retirement
itself is broken, not merely unlucky under contention.

## What "first attempt" actually rules out

```java
ReservationOutcome outcome = reservationService.decide(newOrderId, show.showId(), List.of(seat));
assertThat(outcome).isInstanceOf(ReservationOutcome.Reserved.class);
```

One call, one outcome, asserted `Reserved` directly — not "eventually reserved after a retry," and not
"reserved on a second attempt once the sweeper (if it existed) had a chance to run." SC-016's own
language is "on the first attempt," and this is the assertion that actually enforces that rather than
merely describing it in a comment.

## Confirming the retirement, not just the rebooking

Two further checks close the loop beyond "the new order got in": the OLD reservation's `status` must
now read `EXPIRED` — proving it was actually retired, not merely ignored while the new hold was somehow
granted alongside it — and the new order's own live claim on the seat is confirmed directly by SQL,
joining `reservation_seats` to `reservations` on the new order id. Both facts have to be true
simultaneously for "the seat was legitimately freed and re-let" to be the actual explanation for this
test passing, rather than some other coincidence that happened to leave one seat looking free.
