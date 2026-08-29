# T146 — Specifying SC-003, before `ReservationService` exists

**What this task did:** wrote `ReservationDisjointIT` — 500 concurrent requests for entirely disjoint
seats, asserting 100% succeed — against `ReservationService` and `ReservationOutcome`, both still
unwritten (T158, T160). Confirmed to fail via the compiler for exactly that reason.

---

## The test that catches the opposite mistake from every other test in this batch

Every other concurrency test in this build step asserts that contention produces exactly one winner.
This one asserts the reverse: that the *absence* of contention produces zero losers. That asymmetry is
the entire reason this test earns its own file rather than being folded into `ReservationContentionIT`
as "and also assert unrelated seats succeed."

Consider a mechanism built around a single Redis lock per show — a Redlock-style mutex serialising
every booking attempt for a given show through one key, rejected explicitly in `research.md` R1.
That design would pass `ReservationContentionIT` outright: it cannot double-book seats it processes
one at a time, no matter how many callers are queued behind the lock. It would also pass
`ReservationPartialOverlapIT`, for the identical reason. The only test in this entire batch that a
whole-show mutex actually *fails* is this one — five hundred requests that share nothing with each
other should all succeed simultaneously, and a coarse lock instead serialises them, which under this
test's 60-second deadline reads as requests timing out rather than being granted. Nothing else here
would ever notice that a "correct" implementation was secretly forbidding legitimate concurrency the
spec requires it to allow.

## An exactly-matched pool, not merely a large one

```java
var show = SeatingPlanFixture.provisionShow(jdbcTemplate, "Disjoint", REQUEST_COUNT);
...
i -> reservationService.decide(UUID.randomUUID(), show.showId(), List.of(labels.get(i)))
```

Five hundred seats, five hundred requests, each naming exactly one seat by its own unique index. There
is no seat two requests could name even by coincidence — the disjointness is structural, not merely
probable. That matters because a version of this test drawing randomly from a larger pool would leave
open the possibility that "it happened to work" masks rare, unlucky overlap the test never actually
exercised; naming seats by index removes that ambiguity entirely.

## What "100% succeed" actually asserts

```java
assertThat(outcomes).hasSize(REQUEST_COUNT)
        .allMatch(o -> o instanceof ReservationOutcome.Reserved, ...);
```

Every single outcome, not "most of them" and not "at least 95%." A holding mechanism that invents even
occasional contention on genuinely unrelated seats — a hash collision in some sharding scheme, say, or
a lock scoped more broadly than it needs to be — would show up here as an unexplained refusal among
five hundred requests that structurally cannot conflict, and the test is written to treat even one such
refusal as a failure rather than noise.
