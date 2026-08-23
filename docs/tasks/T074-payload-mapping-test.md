# T074 — Specifying the mapping to `OrderCreated`, before `OutboxWriter` exists

**What this task did:** wrote a test describing how an `Order` becomes the `OrderCreated` message
that announces it — for a class, `OutboxWriter`, that T081 has not yet created.

---

## Confirmed to fail, for the right reason

```text
OrderPayloadMappingTest.java:[43,38] cannot find symbol
  symbol:   variable OutboxWriter
```

Same situation as T073: the class this test needs does not exist, so it will not compile until
T081 builds it. I verified this in isolation — temporarily set the other five new test files aside,
compiled just this one, and confirmed the *only* problem is the missing `OutboxWriter`, not some
unrelated mistake hiding behind it.

## A design decision this test locks in

The full job described for T081 is: build the `OrderCreated` message, capture the trace context
active right now, serialize it, and wrap the result in an `OutboxRecord`. That is four different
concerns bolted together, and testing all four at once — from a plain unit test with no Spring
context — would mean fabricating a `Tracer` and a `Propagator` just to check that a field got copied
correctly.

So this test is written against a narrower slice, and in doing so it *declares* a design choice for
T081 to follow: `OutboxWriter` should expose the pure mapping as its own piece —

```java
OrderCreated event = OutboxWriter.toOrderCreated(order, messageId, occurredAt);
```

— separate from the instance method that adds tracing and serialization on top. A static method with
no dependencies is trivial to test in isolation; tracing gets its own dedicated test later
(`OutboxTracingIT`, T090, once the relay exists in Phase 4). Splitting the concerns this way is not
required by anything in the spec — it is a design decision made now, recorded here so that when T081
is implemented it lands consistent with what this test already expects, rather than the two
disagreeing.

## What each test checks

**`theMappingCarriesEveryFieldAndSagaIdEqualsOrderId`** — walks every field of the produced
`OrderCreated` and checks it against the source `Order`, with two checks singled out because they
are the ones a careless mapping gets wrong silently:

- `event.sagaId()` must equal `event.orderId()` — the correlation rule frozen in build step 1. A
  mapping bug that generated a fresh UUID for `sagaId` instead of reusing the order's own id would
  compile fine and produce a message no consumer could correlate to anything.
- `event.showId()` must equal `order.getShowId()`, never anything that looks like a message
  identity — the exact confusion step 1's rename from `eventId` to `showId` exists to prevent.

**`theSerializedAmountIsAPlainDecimalNeverScientificNotation`** — builds an `ObjectMapper` locally,
matching the settings `JacksonConfig` (T070) will apply, and serializes the mapped event. It checks
the JSON text contains `150.00` and nowhere contains `E+2` — proving the amount comes out as a plain
decimal rather than the scientific notation `BigDecimal` can otherwise produce, which is the exact
defect `WRITE_BIGDECIMAL_AS_PLAIN` exists to prevent.
