# T164 — `ReservationRejectionIT`

**What this did:** wrote the test that specifies SC-008 before any of the code it tests existed —
one deliberately constructed request per refusal cause, checked from four different angles each, so a
future change that gets even one of those four angles wrong has something in place to catch it.

---

## Why four checks per cause, not just "did I get the right enum value back"

It would be easy to write a test that only checks `outcome.reason() == SHOW_NOT_FOUND` and call it
done. SC-008 promises more than that, and each promise needs its own assertion or it isn't actually
being checked:

1. **The right cause, and only that cause.** The returned `ReservationOutcome` really is a `Rejected`
   carrying exactly the expected reason.
2. **No reservation row.** A refusal must never write a row to `reservations` — checked directly
   against `ReservationRepository`, not inferred from the outcome type.
3. **Nothing held.** No seat named in the request — including one that was genuinely free at the
   moment of the attempt — is left with a live claim in `reservation_seats` afterward.
4. **The announcement tells the truth.** The outbox row's stored payload names every seat that was
   requested, not just the one that caused the trouble.

The third and fourth checks are the ones a less careful test would skip, and they're exactly the ones
this project's own house style (a `TRADEOFF:`- and `WHY:`-comment culture, testing real infrastructure
rather than trusting that code "looks correct") exists to insist on. A refusal that quietly holds a
seat it shouldn't, or that reports a narrower seat set than what was actually asked for, would pass a
test checking only the enum value and would still be a real bug.

## Why the third scenario deliberately mixes a held seat with a free one

`seatAlreadyHeldByAnotherOrderIsRejectedAsSeatsAlreadyHeld` doesn't just request one already-held
seat — it requests that seat *and* a second seat that is genuinely still free, together, in one
request. This is what actually exercises "all-or-nothing": a request for only the held seat would
never reveal whether an implementation quietly took the free seat before discovering the other one was
a problem. Requesting both is what forces the free seat to prove, on its own, that it comes out of a
refused request exactly as unheld as it went in.

## Written before the code it tests — and it failed for the right reasons

Run against `ReservationService` before T165 added the seating-plan checks:

- The `SHOW_NOT_FOUND` scenario threw a foreign-key violation trying to write a reservation against a
  show that doesn't exist — because nothing yet stopped `decide(...)` from trying.
- The `SEATS_NOT_FOUND` scenario returned `Reserved` instead of `Rejected` — the service happily
  granted a seat label that doesn't exist in the show's own plan, because nothing yet checked.

Both failures are exactly what should happen before T165 exists, and exactly what stopped happening
once it did.
