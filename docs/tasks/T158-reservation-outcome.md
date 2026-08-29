# T158 — `ReservationOutcome`

**What this task did:** wrote `ReservationOutcome`, the sealed interface T144 through T150 already
specified — `Reserved(reservationId, lockExpiresAt)` or `Rejected(reason)`, and nothing else.

---

## Sealed, and why that's the point rather than a style preference

```java
public sealed interface ReservationOutcome {
    record Reserved(UUID reservationId, Instant lockExpiresAt) implements ReservationOutcome {}
    record Rejected(RejectionReason reason) implements ReservationOutcome {}
}
```

Exactly two cases, permitted explicitly. This is what makes `OutboxWriter`'s eventual `switch`
(T159) exhaustive at compile time rather than by convention: adding a third outcome kind later — "the
decision could not be made," say — without also updating every `switch` over this interface becomes a
compiler error, not a silently unhandled branch discovered the day it first actually happens in
production. `OutcomeMappingTest` (T150) is where that exhaustiveness gets exercised, not merely
asserted in a comment.

## Why there is no third case for "couldn't decide"

It would be tempting to add `Undecidable` alongside `Reserved` and `Rejected`, matching the three
outcomes a request can informally have. This interface deliberately doesn't, because an undecidable
request — the stores are unreachable, FR-047 — never reaches the point of producing a
`ReservationOutcome` at all. It fails the message's *consumption*, so the message is redelivered
rather than answered with an outcome that was never genuinely decided. Adding a case for that here
would invite `OutboxWriter`'s switch to build a message for it, which is exactly the thing FR-047
forbids — none of the frozen refusal causes means "we couldn't tell," and giving this interface a slot
for that idea at all would make the mistake easy to write by accident later.

## Why `lockExpiresAt` travels with `Reserved` rather than being recomputed downstream

This is the one field worth pausing on, because it looks at first like it could be derived instead of
stored. `OutboxWriter` needs a lapse moment to announce, and it would be shorter code to have it
compute `occurredAt.plus(ttl)` itself from a TTL constant it already has access to. That was rejected:
it would create two places — this outcome's own construction inside `ReservationService`, and
`OutboxWriter`'s own computation — that both have to agree on the hold's lifetime, and two places that
agree today can silently drift apart the day only one of them changes. Carrying the exact value through
means the database's own `lock_expires_at` and the announced `lockExpiresAt` are the identical value by
construction, not two independent computations that happen to match.

## Verifying it

Compiled cleanly and, together with T159 and T160, is what finally let the whole test batch (T142–T150)
run rather than merely compile — recorded in full in T160's own write-up, where every test's actual
pass/fail result is reported.
