# T165 — teaching `ReservationService` the seating-plan checks

**What this did:** extended `decide(...)` with the two checks User Story 1 deliberately left out —
does the show exist at all, and does every requested seat label exist within it — so all three refusal
causes this service can ever produce are now actually produced, in the order the contract requires.

---

## Why "does the show exist" has to come before "does the seat exist"

These are two different questions with two different failure meanings, checked in a specific order on
purpose:

- **The show doesn't exist at all.** Nothing about the seats has even been evaluated yet — the
  request named an identifier this service has never heard of, full stop. That's `SHOW_NOT_FOUND`.
- **The show is real, but a seat label isn't part of its plan.** The service knows exactly which show
  was meant; one specific detail about the request is wrong. That's `SEATS_NOT_FOUND`.

Checking show existence first is what keeps the second check meaningful: asking "does seat A1 exist in
show X" when show X doesn't exist at all would be answering a question that doesn't make sense yet.

## Why these two causes are kept separate rather than merged into one "not found"

A seat label that will never exist (`Z99` in a hall whose rows stop at `M`) and a seat that is
currently unavailable but might free up later are fundamentally different situations for whoever is
waiting on the outcome. Collapsing them into one generic failure would leave a caller with no way to
know whether retrying the exact same request could ever possibly succeed. `SHOW_NOT_FOUND` and
`SEATS_NOT_FOUND` both share that same property — retrying changes nothing — which is exactly why they
are distinct from `SEATS_ALREADY_HELD`, the one cause where trying again later might genuinely work
once the current hold expires.

## What `SeatingPlanRepository` already provided, and why nothing new was built for it

Both checks turned out to need no new query at all — `SeatingPlanRepository` (T126) already exposed
exactly the two questions this task needed answered: `existsById(showId)` (inherited for free from
extending `JpaRepository<Show, UUID>`) for the first check, and its own `findExistingSeatLabels(...)`
for the second, which returns however many of the requested labels actually exist so the caller can
compare counts. That repository's own Javadoc, written back in T126, said as much: this was "arriving
in a later task." This is what it looked like once that later task arrived — no design work, just
calling what was already correctly shaped for this exact use.

## Where the new checks live in the decision sequence

`decide(...)`'s work was pulled into a new private `decideOutcome(...)` method, checked in this exact
order: show exists, then every seat label exists, then (unchanged from before) retire anything lapsed,
then attempt the real hold. Each of the first two checks returns immediately on failure — no lapsed-
reservation retirement, no Redis call, nothing — because there is nothing meaningful left to check
once the request itself doesn't refer to something real.

## Verifying it

`ReservationRejectionIT` (T164), written first and already failing for the right reasons before this
task, now passes in full:

```text
Tests run: 3, Failures: 0, Errors: 0 -- ReservationRejectionIT
```

The whole module, run clean end to end: `mvn clean verify` — 48 tests, 0 failures, 0 errors.
