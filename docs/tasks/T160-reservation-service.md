# T160 — `ReservationService`, and the test infrastructure it finally let run for real

**What this task did:** wrote `ReservationService.decide(...)` — the single `@Transactional` method
that retires any lapsed reservation covering the requested seats, attempts the atomic Redis hold, and
records whichever outcome that produced, together, in one transaction. This is the class every test in
T142 through T150 was written against, and finishing it is what finally let that entire batch run as a
normal build rather than merely compile in isolated pieces.

It's also the task where finishing the missing class stopped being the only obstacle: with
`ReservationService` finally in place, the tests could genuinely execute for the first time, and doing
that surfaced three real bugs of my own — one in a shared test base written back in T138, and two in
tests written in T148/T149 — that no amount of reading the code was going to reveal, because none of
them were compile errors. This document covers both: the service itself, and what running it for real
actually found.

---

## The one method, and why ordering inside it is load-bearing

```text
1. retire any lapsed reservation covering these seats     (FR-018, R6)
2. attempt the atomic Redis hold                          (SeatLockStore)
3. record the reservation + its seats, ONLY if granted
4. record the outbox row announcing whichever outcome this was
```

Every step's position matters, not just its presence. Retirement has to happen before the Redis
attempt: Redis frees a seat the instant its TTL lapses, but the old reservation is still `HELD` in
PostgreSQL until this step says otherwise, and skipping it would let `ux_reservation_seat_live` reject
a booking Redis just legitimately granted (research.md R6). The outbox row has to be written in the
same transaction as everything above it, win or lose, so the announcement can never be lost between
commit and publish nor recomputed later against seat state that has moved on (FR-025) — which is why
`decide` calls `outboxWriter.write(...)` unconditionally, for both `Reserved` and `Rejected` outcomes,
rather than only on success.

`decide` is deliberately the ONLY place in this service that writes `reservations`,
`reservation_seats`, and `outbox` — the identical discipline order-service's own
`OrderAcceptanceService` applies to its own two tables, for the identical reason: FR-025's atomicity
claim is only reviewable, rather than merely hoped for, if there's exactly one method to look at.

## This build step's actual scope, stated where a reader will find it

The class only decides between two outcomes right now — every seat granted, or refused as
`SEATS_ALREADY_HELD`. The seating-plan causes (`SHOW_NOT_FOUND`, `SEATS_NOT_FOUND`) arrive with User
Story 2, which extends this same method rather than adding a second one — tasks.md's own note on why
these two stories aren't fully independent. The idempotency guard that must run before this method is
ever called in production arrives with User Story 3; every test in User Story 1 calls `decide` directly,
exactly once per order, so no redelivery-suppression is needed by this class yet.

## `ReservationSeatRepository` — an addition this task needed, not one it was asked for

Retiring a lapsed reservation means releasing every seat it covers — and doing that as a normal,
JPA-tracked mutation (`seat.release(when)`, relying on dirty checking, the same way
`Reservation.expire()` is already mutated and persisted) needs somewhere to load those seat rows from
first. Nothing in T119–T150 asked for this repository; it's added here because `decide` cannot be
written correctly without it. A bulk `UPDATE ... WHERE reservation_id = ?` would have avoided needing
a repository at all, and was rejected specifically because it would make releasing a reservation's
seats a differently-shaped operation from expiring the reservation itself, for no reason connected to
either operation's own logic.

---

## What actually happened the first time this ran for real

Wiring `ReservationService` together let the whole T142–T150 batch execute as one suite for the first
time. It did not go straight to a clean, expected-failures-only result. Three real problems surfaced,
in order:

### Bug 1 — the shared test pool was never sized for genuine concurrency

`InventoryIT`'s pool of 5 connections exists for order-service's own good reason: several cached test
contexts, each opening its full pool, must stay comfortably under a Testcontainers Postgres instance's
connection limit. That reasoning has nothing to do with `ReservationContentionIT` spinning up a
thousand virtual threads that each hold open their own transaction at once. The first real run failed
every heavily-concurrent test with `SQLTransientConnectionException` — pool exhaustion, not anything
wrong with the seat-locking logic.

The fix is `HighConcurrencyIT`, a new base class the four genuinely concurrent tests
(`ReservationContentionIT`, `ReservationPartialOverlapIT`, `ReservationDisjointIT`,
`ReservationVersionIT`) now extend instead of `InventoryIT` directly, widening the pool to 60 and the
connection-acquisition timeout to 5 seconds — both checked against real numbers rather than guessed:
`postgres:16-alpine`'s own default `max_connections` (100, confirmed by starting one and asking it
directly) bounds how far the pool can safely grow, and the production 250ms timeout is the wrong
yardstick for a burst these tests create on purpose.

Getting `HighConcurrencyIT` to actually take effect was its own small discovery: a subclass registering
its own `@DynamicPropertySource` for the identical property key `InventoryIT` already registers does
**not** win — verified directly with a temporary probe, not assumed from order-service's own
`PostgresIT` comment describing the identical limitation. The working fix is a mutable
`protected static int poolSize` field on `InventoryIT`, read lazily by its own supplier; a subclass's
static initialiser — which the JVM runs before Spring ever builds a context — sets the field, and there
is only ever one registration to begin with.

A second, related fix: `HighConcurrencyIT` is annotated `@DirtiesContext(classMode = AFTER_CLASS)`, so
its 60-connection pool closes and its context evicts from Spring's cache the moment each concrete
subclass's tests finish, rather than staying alive alongside whatever else accumulates later in the
same JVM run — without it, a later test with its own uniquely-cached context
(`LapsedRebookingIT`, whose `@TestPropertySource` makes it ineligible to reuse anything already cached)
failed outright with PostgreSQL's own `FATAL: sorry, too many clients already`.

### Bug 2 — a straggler-thread leak

`ReservationContentionIT`'s own `runConcurrently` helper asserted its 60-second deadline was met, but a
thread still blocked past that deadline didn't stop existing just because the assertion failed — it
kept running, kept holding whatever connection it was waiting on, and competed with every later test's
own connections for as long as it took to eventually finish on its own. Fixed by tracking every spawned
thread and interrupting any still alive past the deadline, rather than merely reporting that some were.

### Bug 3 — `entityManager.persist(...)` with no transaction, and why `@Transactional` on the test method would have been the wrong fix

`ReservationVersionIT` and `LapsedRebookingIT` both plant a lapsed reservation directly, bypassing
`ReservationService`, to arrange "a hold that expired a moment ago" without waiting a real 120 seconds.
Both called `entityManager.persist(...)` with no active transaction, which fails outright — Hibernate
requires one to process a `persist` call at all.

The obvious fix — annotate the test method `@Transactional` — would have been wrong, not merely
insufficient: Spring's testing framework treats `@Transactional` on a *test* method specially, wrapping
it in a transaction that rolls back once the test method returns. `ReservationVersionIT` spins up
*separate* virtual threads that open their *own* database connections to call
`ReservationService.decide(...)` — and a separate connection cannot see another transaction's
uncommitted writes under ordinary isolation. The planted row would have been invisible to the very
threads meant to race over it.

The actual fix is `LapsedReservationFixture`, inserting via plain `JdbcTemplate` — the same choice
`SeatingPlanFixture` already made, for a sharper version of the identical reason: with no transaction
active, `JdbcTemplate` commits each statement immediately, which is what makes the planted row visible
to a different connection the instant it's written.

---

## The result, confirmed with a clean build, not assumed

```text
LiveSeatConstraintIT ............. 2/2 pass
ReservationPartialOverlapIT ...... 1/1 pass
OutcomeMappingTest ................ 6/6 pass
SeatKeyTest ....................... 3/3 pass
SeatLockScriptIT .................. 3/9 pass
ReservationContentionIT .......... 0/20 pass (all 20 repetitions)
ReservationDisjointIT ............. 0/1 pass
ReservationVersionIT .............. 0/1 pass
LapsedRebookingIT .................. 0/1 pass

15 passing, 29 failing, 0 errors, 44 total
```

Every single failure traces to the same, single, already-known cause: `lock_seats.lua` and
`release_seats.lua` are still empty stubs (T152), awaiting the developer exercise (T156). An empty
script returns nothing at all, which `SeatLockStore` reads as "not acquired" regardless of whether a
seat was ever actually contended for — so `SeatLockScriptIT`'s six guarantees that need the script to
DO something fail, while its three that assert something should stay untouched pass by coincidence, not
by correctness. `ReservationDisjointIT` and `ReservationVersionIT` fail because nothing is ever granted
at all. `ReservationPartialOverlapIT` passes for a related but opposite reason: with nothing ever
granted, "zero partial holds" is trivially true.

Zero errors. Zero failures attributable to anything other than the empty Lua stubs. That is the
correct, fully-understood state to hand off T156 in.

---

## What happens next

**T156 is `[human]`.** Per the standing project instruction, the Lua bodies are the developer's own
exercise — everything up to this point exists specifically so that task is a body to fill in, not a
mechanism to design. `docs/tasks/T156-seat-lock-scripts-guide.md` (T155) is the guide written for that
task; `contracts/seat-lock-scripts.md` is the contract. **T157** (reviewing that implementation) waits
on T156 being done — there's nothing to review yet. This implementation continues with `T161`
(`LapsedReservationSweeper`) and `T162` (`OutboxRelayPortIT`), neither of which depends on the Lua
bodies existing, and `T163`'s quickstart verification is recorded honestly against the current,
Lua-pending state rather than a fabricated passing one.
