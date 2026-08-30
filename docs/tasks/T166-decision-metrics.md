# T166 — `DecisionMetrics`

**What this did:** gave `ReservationService.decide(...)` three meters — how many holds are granted,
how many are refused and why, and how long a decision takes — so the difference between "this service
is under heavy, legitimate contention" and "this service is broken and refusing everyone" is visible
on a graph rather than something an engineer has to go dig through logs to tell apart.

---

## Why a raw count of refusals isn't enough on its own

Imagine a dashboard with one number: "refusals in the last five minutes: 4,200." That number looks
identical whether it comes from a load test hammering ten popular seats (working exactly as intended —
contention is supposed to produce refusals) or from a bug that deleted a show's seating plan by mistake
(every single request refused, for a completely different and much more urgent reason). One counter
cannot tell these two stories apart, and an engineer paged at 3am needs to be able to tell them apart
in seconds, not minutes.

## What a "tag" is, and why one counter with a tag beats three separate counters

A Micrometer counter can carry extra labels called tags — `inventory.holds.refused` tagged with
`cause=SEATS_ALREADY_HELD` is a different data series on the graph from the same counter tagged
`cause=SHOW_NOT_FOUND`, even though they share one metric name. This project uses exactly one counter,
tagged by the refusal's own `RejectionReason`, rather than three hand-written separate counters (one
per cause). The reason is maintenance, not cleverness: three separate counters would need a fourth one
added by hand, at the right call site, the day a fourth refusal cause is ever introduced — easy to
forget. One tagged counter, reading the tag straight from the enum's own name, starts reporting a
fourth cause automatically the moment that cause exists, with nothing in this class needing to change.

## Why the timer covers the whole decision, granted or refused, rather than two separate timers

It would be tempting to time "how long does a successful booking take" and "how long does a refusal
take" separately. This project's own success criteria specifically want the two *compared* — is
refusing a request meaningfully cheaper or more expensive than granting one — and that comparison is
only easy to make if both numbers live under the same meter, distinguishable later by whatever other
tags or context a query needs, rather than as two differently-named meters someone has to remember to
look up together.

## What this class deliberately doesn't do

It has no test file of its own. Recording a Micrometer counter or timer isn't complex enough logic to
be worth a dedicated test — `OutboxMetrics`, built earlier in this same service, set that same
precedent — and the wiring itself is exercised for free by every test in the suite that calls
`ReservationService.decide(...)`: if the constructor or the three recording calls were broken, those
tests would fail with a dependency-injection or `NullPointerException` error, not silently pass.

## Verifying it

The whole module, run clean end to end after wiring this class into `ReservationService`:
`mvn clean verify` — 48 tests, 0 failures, 0 errors. No test broke by adding a new required
constructor dependency, confirming the wiring is correct.
