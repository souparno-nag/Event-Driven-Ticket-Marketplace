# T159 — `OutboxWriter`

**What this task did:** wrote `OutboxWriter`, the class that turns a decided `ReservationOutcome` into
the outbox row `ReservationService` (T160) will persist alongside everything else. It ports
order-service's own split between a pure mapping and a full write, applied to this service's own
outcome shape rather than to `OrderCreated`.

---

## The same two-method split order-service's own `OutboxWriter` uses, and why it still applies here

```java
public static SagaEvent toMessage(...)   // pure -- OutcomeMappingTest (T150)
public OutboxRecord write(...)           // full job -- ReservationService (T160) actually calls this
```

Order-service's `OutboxWriter` separates `toOrderCreated` (pure) from `writeOrderCreated` (the full
job: map, serialize, capture tracing, wrap into an `OutboxRecord`) for a reason that has nothing to do
with `OrderCreated` specifically — it's about keeping the part worth unit-testing free of I/O. That
reasoning transfers unchanged: `toMessage` is what `OutcomeMappingTest` exercises with no database, no
Redis, no Spring context at all; `write` is what actually needs an `ObjectMapper` and a `Tracer`.

## Reading the trace-capture pattern from order-service rather than reinventing it

```java
Map<String, String> carrier = new HashMap<>();
TraceContext context = tracer.currentTraceContext().context();
if (context != null) {
    propagator.inject(context, carrier, Map::put);
}
```

Copied structurally from order-service's own `writeOrderCreated`, because the underlying fact it
handles — a request's trace is only active *now*, while this row is being built, not later when a
scheduled relay actually sends it — is identical regardless of which message this outbox announces.
The null check is worth restating for this service specifically: `ReservationContentionIT` and its
siblings call `ReservationService.decide(...)` directly from virtual threads with no HTTP request or
consumed Kafka message wrapping them, so there is very often no active span at all in this build step's
own tests. FR-047's own insistence on never announcing a fact that wasn't established applies here in
miniature: a row with nothing to capture must still be a valid, untraced row, not an error.

## Choosing `event_type` from the outcome, never from a caller-supplied literal

```java
String eventType = switch (outcome) {
    case ReservationOutcome.Reserved ignored -> Topics.SEATS_RESERVED;
    case ReservationOutcome.Rejected ignored -> Topics.SEATS_REJECTED;
};
```

This is a small thing worth naming: `write` decides the channel FROM the outcome type itself, rather
than trusting a caller to pass the matching `Topics` constant alongside it. A caller could otherwise
build a `SeatsRejected` message and mistakenly hand it `Topics.SEATS_RESERVED` — nothing about that
would be a compile error, since both are just `String` constants of the same type. Deriving the channel
from the same `switch` that builds the message makes that specific mismatch structurally impossible
rather than merely unlikely.

## Verifying it

`OutcomeMappingTest` (T150) exercises `toMessage` directly and passes — six tests, all green, as part
of the run recorded in full in T160's own write-up. `write`'s own instance-level behaviour (real
serialization, real trace capture) is exercised indirectly through every test that calls
`ReservationService.decide(...)`, since that method is `write`'s only caller in this build step.
