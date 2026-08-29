# T151 — `SeatKey`

**What this task did:** wrote `SeatKey.of(UUID showId, String seatId)`, the class T142's own test
already specified. This is the first task in Phase 3 that turns a failing test green rather than
adding a new failing one.

---

## A plain static utility, on purpose

No Spring annotation, no dependency injection, no configuration read at runtime. `SeatKeyTest` (T142)
already committed to calling this as a bare static method with no application context at all — that
was the contract this file had to satisfy, not a choice made fresh here. A key builder that needed a
`@Value`-injected prefix or any other bean would have been a heavier dependency than the one thing this
class does justifies, and would have made the unit test that already exists impossible to run as a
unit test.

`inventory.hold.key-prefix` exists in `application.yml`, documenting the key's first segment for
anyone reading the configuration — but this class doesn't read it. The format
`seat:{showId}:{seatId}` is a frozen part of `contracts/seat-lock-scripts.md`, not an environment
setting anything should ever vary between deployments.

## What this file resolves, and what it doesn't — yet

Compiled in isolation against `SeatKeyTest.java`, this class satisfies every reference the test makes
— confirmed directly with `javac`, which no longer reports `SeatKey` as an unresolved symbol anywhere
in that file. That is genuinely T142's compile-time obstacle gone.

What it does NOT yet do is let `SeatKeyTest` actually **run** as part of a normal `mvn test`. Six other
files in this same batch (T144–T146, T148–T150) still reference `ReservationService` and
`ReservationOutcome`, neither of which exists until T158 and T160 — and Maven fails the whole module's
compilation the instant any file in it has an unresolved symbol, regardless of how many other files are
already correct. `SeatKeyTest` passing for real, as part of the ordinary build, is confirmed once this
build step's remaining implementation tasks land — recorded honestly in T160's own write-up rather than
asserted here ahead of the evidence.
