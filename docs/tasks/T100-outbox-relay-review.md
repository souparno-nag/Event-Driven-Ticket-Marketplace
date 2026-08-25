# T100: reviewing `pollAndPublish()` against its own contract

T099 wrote the method. T100 is a different exercise: reading it back cold, as if someone else had
written it, and checking it line by line against the twelve guarantees `contracts/outbox-relay.md`
demanded — not just "do the tests pass," but "does this method actually keep the promise it claims
to, for a reason I can point at."

## Why a review step exists at all, separate from the tests passing

Tests catch a method doing the wrong thing. They're much weaker at catching a method doing the right
thing for reasons that don't generalize — a batch size of one in every test, say, hiding a bug that
only shows up at ninety-nine. The honest way to close that gap is to go back to the contract's own
list of guarantees and check each one against the actual code, not just against a green checkmark.

## The twelve guarantees, checked one at a time

1. **Channel comes from the row's own `event_type`.** `record.getEventType()` is read directly onto
   the outgoing `ProducerRecord` — never a hardcoded topic string anywhere in the method. Holds.
2. **Key is the row's `aggregate_id`.** Same pattern: `record.getAggregateId().toString()`. Holds.
3. **Payload sent verbatim, never re-serialized.** `record.getPayload()` goes straight onto the
   `ProducerRecord`'s value with nothing in between — no `ObjectMapper` touches it in this method at
   all. Holds, and this is the one guarantee where "notice what's *missing* from the code" is the
   actual check: the bug this guarantees against is adding a step, not removing one.
4. **`PUBLISHED` only after the broker acknowledges.** `kafkaTemplate.send(producerRecord).get()` is
   called and allowed to throw *before* `record.markPublished(...)` runs on the next line. `.get()` is
   a blocking call — the method genuinely waits for the future, rather than firing the send and moving
   on. Holds.
5. **A `PUBLISHED` row never sent again.** This one lives entirely in `claimBatch` (T094), which never
   returns a row already in that state. Nothing in `pollAndPublish()` itself could violate it even by
   accident, since it never re-queries for rows outside what `claimBatch` already filtered. Holds.
6. **Failure leaves the row `PENDING`, with `attempts` and `last_error` updated.** The `catch` block
   calls `record.recordFailure(describeFailure(e))` — a single call the entity itself is responsible
   for translating into both field updates. Nothing here marks the row `PUBLISHED` or otherwise leaves
   it in a state pretending the send worked. Holds.
7. **`maxAttempts` reached → `PARKED`, never retried again.** Checked immediately after
   `recordFailure`, in the same catch block, using `>=` rather than `==` — deliberately tolerant of a
   row that somehow already exceeds the threshold rather than assuming it lands exactly on it. Holds.
8. **One row's failure doesn't abandon the rest of the batch.** The `try`/`catch` is written *inside*
   the `for` loop, wrapping only the per-row send-and-mark logic — not wrapped around the loop itself.
   A thrown exception on row 3 is caught right there and the loop simply continues to row 4. Holds.
9. **Stored trace context reaches the outgoing headers.** `attachTraceHeaders` extracts the stored
   `traceparent`/`tracestate` into a real span, then re-injects that span's context into the message's
   headers before it's sent. Holds.
10. **No stored context → still sent, untraced, no error.** `attachTraceHeaders` returns immediately
    if `record.getTraceparent()` is null, leaving the message headers untouched — no header is
    invented, and nothing in that path can throw. Holds.
11. **Two relays never send the same row.** Provided by `claimBatch`'s row locking, not by this
    method — but this method could still break it by sending without waiting. It doesn't: every send
    is followed immediately by `.get()` before the loop moves to the next row, so no row is ever
    "in flight, unconfirmed" while another thread's claim could plausibly interleave with it. Holds.
12. **Rows for one order arrive in recording order.** Also mostly `claimBatch`'s guarantee — broken
    only by not processing the claimed list in the order it came back in, or by sending several rows
    concurrently. The method does neither: it's a plain sequential `for` loop over `claimed`, each
    iteration blocking on its own send before the next begins. Holds.

All twelve check out, and all twelve are additionally exercised by name in the Phase 4 test suite,
which passes cleanly and repeatably in isolation (three clean runs of all fourteen tests across this
build step's work, most recently right before this review).

## The one thing worth changing

Everything else in the file matched house style on inspection — `WHY` comments on the non-obvious
lines, no `WHAT` comments restating what the code already says, no Lombok, no abstract base class
standing in for a fifteen-line method. One inconsistency turned up: the javadoc explaining why
`@Transactional(timeout = 30)` overrides the project's usual 3-second default is exactly the kind of
real, named tradeoff (a specific alternative — inheriting the 3-second default — considered and
rejected, with a concrete reason) that every other genuine tradeoff in this codebase marks with a
literal `TRADEOFF:` tag (see `OrderController`, `JacksonConfig`, `OutboxRepository`, `Order`). This
one didn't have the tag, even though it read exactly like one. Fixed by adding it — a one-word,
purely cosmetic change with no effect on behavior, made so a future reader searching this codebase for
`TRADEOFF` to audit its deliberate design decisions actually finds this one instead of missing it.

## Verdict

Kept as written, with that one tag added. Nothing here needed a rewrite — the guarantees hold for
reasons traceable to specific lines, not by coincidence, and the two failures seen once (in a
combined full-suite run, not this isolated one) were traced back separately to a test-timing budget
issue under heavy load, not to anything this method itself gets wrong — see
`docs/tasks/T099-outbox-relay-implementation.md` for that investigation.
