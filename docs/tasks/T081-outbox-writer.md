# T081 — `OutboxWriter`, and a bug it uncovered in earlier work

**What this task did:** wrote the class that turns an accepted order into the outbox row that will
eventually announce it — and, in doing so, exposed a real defect left over from Phase 2 that nothing
had exercised until now.

---

## Two responsibilities, kept apart on purpose

```java
public static OrderCreated toOrderCreated(Order order, UUID messageId, Instant occurredAt)   // pure

public OutboxRecord writeOrderCreated(Order order)   // the full job
```

The static method is exactly what `OrderPayloadMappingTest` (T074) has been specifying: no I/O, no
tracing, nothing but copying fields from an `Order` into a fresh `OrderCreated`. The instance method
builds on it — maps, serializes, captures the trace context — and is what
`OrderAcceptanceService` (T082) actually calls.

Splitting these two apart is not something any requirement demanded; it is a design choice made
*while writing T074's test*, because testing the mapping alone needs no Spring context and no
tracer, and runs in milliseconds. Recorded here because it is the reason this class has the shape it
does, not an accident of how the code came out.

## Capturing a trace that will otherwise be gone

```java
TraceContext context = tracer.currentTraceContext().context();
if (context != null) {
    propagator.inject(context, carrier, Map::put);
}
```

The outbox exists to let a request finish and return *before* its message is sent — the relay does
that later, on its own schedule. But a request's trace is only active while the request is being
handled. By the time the relay runs, minutes may have passed and the original trace context is gone
from every thread that once carried it. So it has to be captured **now**, while this row is being
built, and carried on the row itself until the relay is ready to use it.

The `null` check matters in practice, not just in theory: calling this method from a plain
`@Transactional` service method with nothing wrapping it in an HTTP request — exactly what
`OrderAcceptanceIT` does — leaves no active span to capture. Handling that as "store nothing, send it
anyway" rather than throwing is what FR-027 asks for, and it is what keeps this method safe to call
from a test that has no web server around it at all.

## Why the channel name can never be a literal

```java
return new OutboxRecord(order.getId(), Topics.ORDER_CREATED, payload, ...);
```

`Topics.ORDER_CREATED` comes from `common-events`, the module that has owned every channel name
since build step 1. Writing `"order.created"` by hand here instead would compile identically and
fail completely differently: a typo in a hand-typed string produces no error anywhere — the message
just lands in a channel nothing subscribes to, and the saga stalls with every service still reporting
healthy. Depending on the constant turns that mistake into a compile error instead.

---

## The bug this class's tests uncovered

Running the first version of this batch's tests, every one that actually persisted an `OutboxRecord`
failed with the same PostgreSQL error:

```text
null value in column "created_at" of relation "outbox" violates not-null constraint
```

`OutboxRecord` (built in T067, two batches ago) never set `createdAt` before being saved. `Order`,
built in the very same phase, had a `@PrePersist` callback for exactly this reason —
`OutboxRecord` was missing its counterpart, and nothing in Phase 2 had ever actually inserted a row,
so the gap went unnoticed until this task tried to.

The column is declared `NOT NULL DEFAULT now()`, but that default only fires when a column is left
**out** of an `INSERT` entirely. Hibernate always names every mapped column explicitly, and a Java
field that was never set is `null` — so Hibernate sent an explicit `NULL`, which overrides the
database's default rather than falling through to it. The fix is the same shape as `Order`'s:

```java
@PrePersist
void onInsert() {
    this.createdAt = Instant.now();
}
```

This is a correction to already-committed code, made here because this is the first task whose
tests actually exercised the path that was broken. Recorded plainly rather than folded in silently:
`OutboxRecord.java` from T067 gained this callback as part of this task's commit.

---

## Confirmed

Every test in this batch that persists an outbox row now passes, with the fix in place:
`OrderAcceptanceIT` (all three scenarios), `OrderApiIT` (all seven), `OrderCapacityIT`. Full reactor
build: `BUILD SUCCESS`.
