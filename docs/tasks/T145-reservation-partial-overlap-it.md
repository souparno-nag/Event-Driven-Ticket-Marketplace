# T145 — Specifying SC-002, before `ReservationService` exists

**What this task did:** wrote `ReservationPartialOverlapIT` — 500 concurrent requests, each for three
seats deliberately overlapping its neighbours, asserting that not one of them is ever granted a partial
hold — against `ReservationService` and `ReservationOutcome`, both still unwritten (T158, T160).
Confirmed to fail via the compiler for exactly that reason, the same way as T144.

---

## What this proves that SC-001 cannot

It would be easy to assume T144 already covers this ground — it also asserts an exact, contended
outcome. It doesn't, and the reason is specific: every request in SC-001 asks for exactly *one* seat.
An all-or-nothing hold is trivially all-or-nothing when there is only one thing to hold — there is no
"some but not all" state available for the mechanism to produce even if it wanted to. This test's
requests each ask for *three*, deliberately overlapping their neighbours by two of them, which is what
finally gives "granted two of my three seats, refused on the third" somewhere to actually happen —
exactly the failure mode `contracts/seat-lock-scripts.md`'s own "Traps this contract exists to
prevent" section names first: a script that checks and sets key-by-key rather than in one atomic pass.

## Deterministic overlap, not random overlap

```java
List<String> seats = List.of(labels.get(i % SEAT_COUNT), labels.get((i + 1) % SEAT_COUNT), labels.get((i + 2) % SEAT_COUNT));
```

Request *i* and request *i + 1* share two of their three seats, by construction, for every one of the
500 requests. A version of this test built on randomly chosen seats would also create overlap, but a
failure it uncovered would be far harder to reproduce — the exact seat sets that triggered a bug would
differ on every run, turning "this test failed" into "this test failed on some unknown combination
today." The deterministic version fails the identical way every time it fails at all, which is worth
far more when something eventually needs debugging.

## Verifying "holds nothing" for a refusal, not just "holds fewer seats than requested"

```java
Integer heldCount = jdbcTemplate.queryForObject(
        "SELECT count(*) FROM reservations WHERE order_id = ?", Integer.class, attempt.orderId());
```

A refused request's assertion isn't "the seats it holds are a subset of what it asked for" — it's that
no `Reservation` row exists for that order at all. That's a deliberately stronger claim: a mechanism
that granted two of three seats and then correctly rolled them back on discovering the third was taken
would satisfy a weaker "subset" check while still having briefly held seats it had no business
holding — precisely the "look at each seat, see it is free, then take it" pattern the spec's own User
Scenarios section names as wrong, because every other contender is doing the same thing in the gap
between the look and the take.

## What a granted request's assertion actually compares

```java
Set<String> actuallyHeld = ...; // queried directly from reservation_seats
if (!actuallyHeld.equals(Set.copyOf(attempt.requestedSeats()))) { ... }
```

Set equality, not size equality and not "contains all requested seats." A hold that grabbed the
requested three *and* a fourth seat by accident would pass a weaker check and fail this one — which
matters, because a bug that hands out an extra seat nobody asked for is exactly as serious as a bug
that hands out too few.
