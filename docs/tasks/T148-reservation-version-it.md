# T148 — Specifying SC-011, before `ReservationService` exists

**What this task did:** wrote `ReservationVersionIT` — two orders racing to rebook the same
just-lapsed seat, asserting exactly one wins, the loser is detected rather than silently overwritten,
and the loser's retry lands on an ordinary refusal rather than an unhandled error — against
`ReservationService` and `ReservationOutcome`, both still unwritten (T158, T160). Confirmed to fail
via the compiler for exactly that reason.

---

## Why this scenario, and not a synthetic update to an arbitrary field

`@Version` (FR-012) exists to protect concurrent *updates* to a `Reservation` row — but this build
step's own code path produces exactly one situation where two updates to the same existing row can
ever collide: inline retirement of a lapsed reservation (FR-018). Two brand-new orders discovering the
identical stale hold at the identical moment, both trying to retire it as part of taking their own new
hold, is the only genuine race this service creates on its own between now and step 4. Testing
anything else — updating a made-up field on a `Reservation` twice from two threads, say — would prove
Hibernate's optimistic locking works in the abstract, which nobody doubted, rather than proving it
works at the one place this service's own logic actually needs it to.

## Why the test doesn't expect both threads to somehow succeed

Only one seat exists in this test. Once the loser's version conflict is detected and it retries — per
FR-013, exactly once — its retry runs against the *already-updated* state: the winner's fresh hold is
in place, the stale reservation is already retired. The loser's retry therefore lands on an ordinary
`SEATS_ALREADY_HELD` refusal, not a special error state. That's SC-011's own "or failing visibly"
branch, and it's worth being explicit about what this test does *not* attempt: SC-011 also names a
narrower case — a *second* version conflict surfacing as an explicit processing failure rather than a
seat refusal — which describes a rarer three-way race. Engineering that deterministically would need a
way to pause execution mid-retry that `ReservationService` has no reason to expose, so this two-thread
test does not attempt it; the comment in the test file says so plainly rather than silently claiming
broader coverage than it has.

## Planting the race rather than waiting for it

```java
Reservation stale = new Reservation(..., Instant.now().minusSeconds(1));
```

A hold's real lifetime is 120 seconds. Waiting for one to genuinely lapse before testing what happens
next would make this test slow for no benefit — the reservation is planted directly, already lapsed,
via `EntityManager`, bypassing `ReservationService` entirely for *this* piece of setup. That's a
deliberate, narrow use of direct persistence purely to arrange the starting condition; the race itself,
and everything this test actually asserts about, still runs through `ReservationService.decide(...)`.

## What "no silent overwrite" actually means as an assertion

```java
assertThat(versionAfterRace).isEqualTo(versionBeforeRace + 1);
```

Not "the version changed" — exactly one increment. A version left completely unchanged would mean
nobody actually retired the stale reservation (and the winner's own insert should then have failed the
live-seat constraint instead of succeeding). A version incremented twice would mean both racers' updates
somehow both landed — the literal silent overwrite `@Version` exists to make impossible. Checking the
exact delta is what turns "the mechanism didn't visibly break" into "the mechanism did exactly one of
the two things it's allowed to do."
