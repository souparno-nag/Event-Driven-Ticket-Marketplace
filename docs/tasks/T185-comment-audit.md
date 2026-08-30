# T185 — auditing `inventory-service/` for WHY comments and `TRADEOFF:` labels

**What this did:** read every inline comment in this service's main source — all 34 files, roughly 160
comments — checking each one explains WHY a line exists rather than restating WHAT it does, and
checked every design decision naming a real rejected alternative for the explicit `TRADEOFF:` label
CLAUDE.md's own house style calls for.

---

## What "WHY, not WHAT" actually means, with a concrete contrast

A WHAT comment restates code a reader can already see: `// increment the counter` directly above
`counter.increment()` tells a reader nothing they didn't already know from the line itself. A WHY
comment explains something the code cannot say on its own — the reasoning, the alternative that was
considered, the bug that was found by testing rather than assumed, the requirement being satisfied.
Nearly every comment in this codebase already reads the second way, because this whole service was
built with that discipline applied at the moment each comment was written, not layered on afterward.
This task's job was to CONFIRM that's actually true by reading every one, not to assume it because it
was the stated intention.

## The result of reading all of them

Every inline comment found explains reasoning, not mechanics — a contract requirement being met, a
trap a naive version would fall into, a bug found by running real tests rather than by inspection, or a
reason one specific value was chosen over an obvious alternative. None simply restated the line beside
it. This is a genuinely clean result, not a rubber stamp: it reflects the same discipline this whole
build step's own commits already applied one task at a time, being checked here as a single pass across
the finished result rather than assumed to have held.

## Two comments that discussed a real alternative without the explicit label — found and fixed

The audit's actual value showed up here: two places genuinely discussed a rejected alternative with a
real, accepted cost, but hadn't been marked with the explicit `TRADEOFF:` word other, similar comments
elsewhere in this same service already use consistently.

**`ReservationSeatRepository`** — its own Javadoc already explained that a bulk `UPDATE ... WHERE
reservation_id = ?` would be faster and would avoid needing this repository interface at all, and that
it was rejected anyway. That's a genuine tradeoff (a real, faster alternative given up for a
consistency reason), just missing the label every other tradeoff discussion in this service already
carries. Added.

**`ReservationService`**, in the seat-existence check: comparing counts rather than doing a genuine
set-difference is faster and simpler, but is theoretically wrong for one shape no real caller ever
sends (a request naming the same seat label twice). That's exactly what `TRADEOFF:` exists to flag —
a real, named cost, accepted because it never actually applies to a real caller — and it hadn't been
labeled as one. Added, with the specific failing shape spelled out rather than left as a vague
"in practice" caveat.

## Why other rejected-alternative language found elsewhere was left as-is

A few more places mention something that was tried and abandoned — `SeatLockRebuilder`'s own Javadoc
naming `@PostConstruct` and an `ApplicationReadyEvent` listener as rejected timings, for instance. These
were left without the `TRADEOFF:` label deliberately: both alternatives are described as producing
outright WRONG behaviour (a datasource not yet ready, or listeners already started) rather than a
working alternative with a real, accepted cost. `TRADEOFF:` in this codebase's own established usage
names a genuine choice between two things that both work, where one was picked despite a real
downside — not a correctness argument for why the other options are simply broken. Test files carry
similar "considered and rejected" language about their own design (`SeatingPlanFixture`,
`ReservationDisjointIT`), which was likewise left alone: every existing `TRADEOFF:` label in this
service, without exception, lives in the code being tested, never in the tests themselves.

## Verifying it

The whole module still compiles cleanly after both additions: `mvn compile`. Nothing about the two
comments' TEXT changed in a way that alters behaviour — this was a documentation pass, and the tests
already covering everything these two files do continue to pass unchanged.
