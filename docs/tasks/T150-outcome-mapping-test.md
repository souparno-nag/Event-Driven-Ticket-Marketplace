# T150 — Specifying `OutboxWriter`'s mapping, before it exists

**What this task did:** wrote `OutcomeMappingTest`, a unit test for the pure function that turns a
decided `ReservationOutcome` into the exact message it produces — against `ReservationOutcome` (T158)
and `OutboxWriter` (T159), neither written yet. Confirmed to fail via the compiler for exactly that
reason: `package com.marketplace.inventory.service does not exist`.

---

## A unit test, deliberately, closing out the batch that started with one

This is the second and last unit test in this batch (`SeatKeyTest`, T142, was the first) — no database,
no Redis, no Spring context, matching how `tasks.md` itself categorizes it. That's possible because the
mapping from a decided outcome to a message is pure data transformation, and it's worth being explicit
about what that implies for `OutboxWriter`'s eventual shape: this test specifies a **static, pure**
method — `OutboxWriter.toMessage(orderId, seatIds, outcome, occurredAt)` — deliberately separated from
whatever *instance*-level work the real T159 will also need to do (serializing with the configured
`ObjectMapper`, capturing trace context, wrapping the result into a persistable `OutboxRecord`). Testing
the pure mapping in isolation from the I/O-bound parts is what keeps this test a unit test at all;
folding everything into one instance method would have forced this test to become an integration test
for no reason connected to what it's actually checking.

## Why "the mapping is total" is a testable claim rather than a slogan

`ReservationOutcome` is specified (T158, via `ReservationVersionIT` and every other test in this batch)
as a **sealed** interface with exactly two cases — `Reserved` and `Rejected`. That's what makes
`OutboxWriter`'s internal `switch` exhaustive by construction: the compiler itself refuses to build
`OutboxWriter` if a case is left unhandled, and refuses to compile a *third* case onto
`ReservationOutcome` without every existing `switch` over it being revisited. "The mapping is total" is
therefore not a property this test proves so much as a property the *type system* proves, with this
test confirming the two cases that do exist map to the *correct* messages rather than merely to *some*
message each.

## `lockExpiresAt` travels with the outcome, not recomputed at write time

```java
record Reserved(UUID reservationId, Instant lockExpiresAt) implements ReservationOutcome
```

This is a real design decision worth naming rather than leaving implicit: `Reserved` carries the exact
`lockExpiresAt` the database will store on the `Reservation` row, and `OutboxWriter` reads it straight
through rather than recomputing `occurredAt.plus(ttl)` itself from a duplicated TTL constant. The
alternative — letting `OutboxWriter` independently derive the lapse moment from a TTL it also has to
know about — creates two places that both have to agree on the hold's lifetime, and two places that
agree today can silently drift apart the day one of them changes. Carrying the value through means the
announced `lockExpiresAt` and the database's own `lock_expires_at` are not merely *supposed* to
match — they are, by construction, the identical value.

## Verifying the full requested set survives a refusal, not only the contended seat

```java
void aRejectionReportsTheFullRequestedSetNotOnlyWhicheverSeatWasUnavailable()
```

FR-023 requires a refusal to report every seat originally requested, not merely whichever one was
actually contended — because the request was refused as a whole, and understating what was lost would
misstate what happened to the buyer. This test passes three seats into a rejection and confirms all
three come back, which is the one assertion that would catch a mapping that quietly narrowed the set
down to "the seat that mattered" along the way.

---

This closes out User Story 1's failing-test phase (T142–T150). Nine files, seven of them written
against classes that do not exist yet and confirmed to fail for exactly that reason; one
(`LiveSeatConstraintIT`, T147) needing nothing unwritten and verified passing today. What remains
before any of the other eight can turn green is Phase 3's implementation sequence: `SeatKey` (T151),
the two Lua scripts and their surrounding machinery (T152–T157), `ReservationOutcome` and
`OutboxWriter` (T158–T159), and `ReservationService` itself (T160) — the class every one of these
tests has been written against, and the class that does not yet exist.
