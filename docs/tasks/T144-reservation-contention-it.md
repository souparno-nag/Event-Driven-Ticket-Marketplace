# T144 — Specifying SC-001, before `ReservationService` exists

**What this task did:** wrote `ReservationContentionIT` — 1,000 concurrent booking requests against a
pool of exactly 10 seats, repeated 20 times, asserting exactly 10 succeed and exactly 990 refuse every
single time — against `ReservationService` and `ReservationOutcome`, neither of which T158/T160 have
written yet.

---

## Confirmed to fail, for the right reason

```text
package com.marketplace.inventory.service does not exist
cannot find symbol: class ReservationService
```

Confirmed via Maven's own compiler output, cross-checked against `javac` run directly on the whole
batch: every error in this file traces to the one package that doesn't exist yet. Nothing else.

## Why direct calls, not the channel — restated at the one test where it matters most

`research.md` R10 and FR-040 already settle this, but it's worth restating exactly why *this specific
test* is the one where the distinction is not academic: `order.created` has three partitions, frozen
in step 1. Three partitions means at most three messages from this topic are ever being processed at
the same literal instant, regardless of how many are queued behind them. A genuinely broken
all-or-nothing hold — one that checks and sets seats one at a time, say — has a real chance of
surviving a three-way race by sheer luck. It has essentially no chance of surviving a thousand-way one.
Calling `ReservationService.decide(...)` directly from a thousand virtual threads is what makes "by
luck" an implausible explanation for a passing result, which a channel-driven version of this exact
test could never claim.

## The latch discipline, and why it's not decoration

```java
CountDownLatch ready = new CountDownLatch(count);
CountDownLatch go = new CountDownLatch(1);
```

Every thread counts `ready` down the instant it starts, and none of them proceeds until every last one
has done so. Without this, thread #1 might already be halfway through deciding its outcome by the time
thread #1000 has even been scheduled — which would mean the test measures contention diluted by
however long the JVM took to spin up a thousand threads, not contention among a thousand callers
genuinely racing at the same moment. This is the exact discipline research.md R10 names, applied
literally rather than merely cited.

## Why 20 repetitions, and why each needs its own show

SC-001 demands repeatability "across at least 20 consecutive runs with no run deviating" — a
single-pass "it worked once" result is exactly the kind of evidence a subtly broken script could
produce by accident. `@RepeatedTest(20)` is the mechanical answer; provisioning a *fresh* show inside
every repetition, via `SeatingPlanFixture`, is the part that's easy to get wrong by reusing state: a
hold's 120-second lifetime is far longer than a single repetition takes to run, so reusing one show
across all 20 repetitions would mean the first repetition's ten winners are still holding every seat
when the second one starts, and every repetition after the first would trivially fail with nothing
free to contend for — not because the mechanism is broken, but because the test forgot to give it
anything to contend over.

## What the assertions actually check, beyond the headline count

Counting outcomes alone would pass even if the ten "wins" somehow landed on the same seat twice and
missed another entirely. Two further checks close that gap: every refusal's stated cause must be
`SEATS_ALREADY_HELD` specifically — a refusal for the wrong reason would be a different bug wearing this
test's costume — and a direct SQL count of live `reservation_seats` rows must equal exactly ten
*distinct* seat labels, which is the database-level confirmation that "granted" and "genuinely holding
one seat, once" are the same fact rather than two things this test merely assumed agreed.
