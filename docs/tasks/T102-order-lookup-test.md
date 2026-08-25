# T102: writing the test for reading an order back, before the endpoint exists

This build step so far has only ever let you *create* an order — `POST /api/orders`. Nowhere yet can
a caller ask "what happened to the order I just created?" That's User Story 3: a single new endpoint,
`GET /api/orders/{orderId}`, that reports an order's current state.

T102 doesn't write that endpoint. It writes the test that describes exactly what the endpoint must
do — before a single line of the endpoint itself exists. This is the same discipline `OrderApiIT`
already established for the write side of this API, continued here for the read side.

## Why write the test first, and why it's expected to fail today

A test written *after* the code it checks tends to describe whatever the code already does, whether
or not that's actually correct — it's very easy to accidentally write a test that just confirms "the
code does what the code does." A test written *before* the code has no such code to lean on; it can
only describe what the contract (`contracts/orders-api.yaml`) says should happen. That's a stronger
kind of test, and it's why this file is deliberately created now, one task before `OrderView` (T103)
and the actual `GET` mapping (T104) exist.

Running it right now produces four failures, and that's the correct, intended outcome — not a bug in
the test:

```
OrderLookupIT.readingAnAcceptedOrderReturnsEveryFieldUnchanged: Status expected:<200> but was:<404>
OrderLookupIT.unknownIdentifierIsReportedAsNotFound: Content-Type expected:<application/problem+json> but was:<null>
OrderLookupIT.malformedIdentifierIsReportedAsABadRequest: Status expected:<400> but was:<404>
OrderLookupIT.notFoundAndMalformedIdentifierUseDistinctProblemTypes: Status expected:<400> but was:<404>
```

Every single request comes back `404`, because Spring has no route registered for
`GET /api/orders/{orderId}` at all yet — that `404` is Spring's own generic "nothing here," not this
service's own answer to "no such order." Once T104 adds the route, these same four tests become the
acceptance check for whether it (and T103, T105 alongside it) got the contract right.

## What the test actually checks

**Reading back what was submitted (FR-020, SC-010).** The test first uses the *already-working*
`POST /api/orders` endpoint to create a real order, exactly the way any real caller would, then reads
it back and checks every field the caller originally sent: `userId`, `showId`, `seatIds`, `amount`.
One deliberate detail: the two seats submitted (`"A1"`, `"A2"`) are already in sorted order. `OrderView`
(T103) is specified to return seats sorted, for a predictable response — submitting them pre-sorted
means this test can check "the seats came back correctly" without that check being confused by an
intentional, documented reordering the endpoint is supposed to do.

**Telling "unknown" apart from "malformed" (FR-021).** Two different things can go wrong with an
identifier: it might be a perfectly valid UUID that simply doesn't belong to any order (`404`), or it
might not even be a UUID at all (`400`) — someone fat-fingered a URL, say. Both are tested separately.

**Proving the two failures are actually distinguishable, not just two different numbers (FR-021,
again, more strictly).** A caller's code rarely branches on the raw HTTP status number alone in a
system built around RFC 7807 problem details — it looks at the response body's `type` field, a
stable URI meant to be checked, not just glanced at. So one more test doesn't just check that `404`
and `400` happen on their own; it fetches both responses and directly compares their `type` values,
proving they're genuinely different strings — not merely two tests that happened to hit different
status codes without anyone checking whether their bodies would actually let calling code tell them
apart.

## What's still to come

T103 gives the endpoint something to return (`OrderView`, with seats pre-sorted). T104 wires the route
itself. T105 teaches the exception handler what a "no such order" problem looks like — the malformed
identifier's `400` is expected to come from Spring's own path-variable conversion machinery, which is
why T105 is scoped only to the `404` side. Once all three land, this file should turn green with no
changes needed to it at all — that's the whole point of writing it now.
