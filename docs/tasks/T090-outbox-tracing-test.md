# T090 — Specifying trace continuity across the outbox gap

**What this task did:** wrote the test for guarantees 9 and 10 — the stored trace context reaching
the outgoing message's headers, and a row with none still sending cleanly — completing the story
`OutboxWriter` started in T081.

---

## Confirmed to fail, for the right reason

```text
OutboxTracingIT.java:[37,17] cannot find symbol
  symbol:   class OutboxRelay
```

Same missing class as `OutboxRelayIT`. Verified in isolation alongside it.

## Two halves of one story, built two batches apart

T081 gave `OutboxWriter` the job of capturing a trace context **onto the row**, at the moment it is
written — because the request's own trace is only alive while the request is being handled, and gone
by the time the relay eventually runs. This task's test is the other half: proving the relay takes
whatever was captured and puts it **back into the outgoing message**, so a trace that started with a
buyer's HTTP request and continued through the database write appears, in the tracing UI, as one
connected story rather than two unrelated fragments.

## Simulating a request's trace without a request

```java
Span span = tracer.nextSpan().name("test-accepting-request").start();
try (Tracer.SpanInScope scope = tracer.withSpan(span)) {
    traceId = span.context().traceId();
    record = outboxWriter.writeOrderCreated(order);
} finally {
    span.end();
}
```

`continuesTheOriginalTrace` needs a genuinely active span while the outbox row is built — exactly
the situation a real HTTP request creates automatically, which Spring's own tracing instrumentation
wraps around every incoming request without this test needing to ask for it. Since this test calls
`OutboxWriter` directly rather than going through an HTTP call, it starts and holds a span by hand,
using the real `Tracer` bean, for precisely the duration `OutboxWriter.writeOrderCreated` runs — then
lets it end, the same way a real request's span ends the moment that request returns. Everything
after that point in the test — saving the row, calling the relay — happens with **no active span at
all**, which is the honest simulation of the gap the outbox pattern creates.

## Checking the header without over-specifying its exact shape

```java
String headerValue = new String(header.value(), StandardCharsets.UTF_8);
assertThat(headerValue).contains(traceId);
```

The W3C `traceparent` header has an exact byte format (`00-<trace-id>-<span-id>-<flags>`), and this
test deliberately does not reconstruct that format to compare against. Doing so would duplicate
Micrometer's own serialization logic inside a test, coupling the test to an implementation detail of
a library this project doesn't own. Checking that the header **contains** the original trace id is
the honest version of the claim being tested — "this message continues that trace" — without
claiming to also verify exactly how Micrometer writes W3C headers, which is Micrometer's job to get
right, not this test's.

## The untraced case: no header invented, and nothing broken

```java
OutboxRecord record = new OutboxRecord(aggregateId, Topics.ORDER_CREATED, "{}", null, null);
```

`sendsUntracedRecord` builds a row directly against the repository rather than through `OutboxWriter`
— so there genuinely is no trace context stored, `traceparent` and `tracestate` both `null` from the
start. FR-027's requirement is exactly this case: the relay must still send it, without error, and
without fabricating a header that was never there. Both are checked — the message arrives, and the
header is absent, not merely unchecked.
