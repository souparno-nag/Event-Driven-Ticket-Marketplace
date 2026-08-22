# T027 — Writing down why the envelope is duplicated

**What this task did:** added a `TRADEOFF:` comment to `SagaEvent.java` recording why the four
envelope components are repeated on all seven records instead of being extracted into a wrapper.

No behaviour changed. This task produces a comment — and the reason a comment is worth its own task
is the substance of this document.

---

## The duplication in question

Every one of the seven records begins the same way:

```java
public record OrderCreated(UUID messageId, UUID sagaId, Instant occurredAt, int schemaVersion, ...)
public record SeatsReserved(UUID messageId, UUID sagaId, Instant occurredAt, int schemaVersion, ...)
public record SeatsRejected(UUID messageId, UUID sagaId, Instant occurredAt, int schemaVersion, ...)
...
```

Twenty-eight component declarations for four distinct fields. Any experienced developer reading this
will immediately want to factor it out — that reflex is well trained and usually right.

So the file needs to say why it was not.

---

## The alternative that was rejected

```java
record Envelope<T>(UUID messageId, UUID sagaId, Instant occurredAt, int schemaVersion, T payload) {}

Envelope<OrderCreated> message = ...;
```

Defined once. Every message wraps its own payload. Clean, DRY, and the obvious answer.

It loses on the **wire format** it produces:

```json
{
  "messageId": "...",
  "sagaId": "...",
  "occurredAt": "2026-08-22T09:15:30.123456789Z",
  "schemaVersion": 1,
  "payload": {
    "orderId": "...",
    "seatIds": ["A12", "A13"],
    "amount": 49.99
  }
}
```

Everything a consumer actually cares about is now one level down. Consider who pays for that:

- **Every consumer** unwraps before reading a business field.
- **Every JSON schema** in `contracts/` gains a nesting level.
- **Every debugging session** — `jq '.payload.seatIds'` instead of `jq '.seatIds'`. Every log query
  gains a prefix.
- **Every schema evolution question doubles**: did the envelope version change, or the payload's?
  With one flat shape, `schemaVersion` describes the whole message and there is nothing to
  disambiguate.

Now compare the costs honestly:

| | Cost | Paid by | How often |
|---|---|---|---|
| Duplication | 24 extra component declarations | Us | Once, while typing |
| Wrapper | Permanent nesting in the wire format | Every consumer, every reader, every tool | Forever |

The duplication is visible, boring, and finite. The nesting is invisible in the Java source and
compounds everywhere the JSON is read. That is why the flat shape wins.

## The option that was never available

Worth noting because it is the first thing most people reach for: **records cannot extend a class.**
There is no `abstract class BaseEvent` with the four fields that `OrderCreated extends`. Java
forbids it outright.

A shared *interface* can declare accessors — which is exactly what `SagaEvent` does — but an
interface cannot hold state. So there is no version of "define the envelope once" that also keeps
the JSON flat. The choice really is between duplication and nesting.

---

## Why a comment is a deliverable

This task adds no code. It exists because of a specific failure mode:

> Six months from now, someone opens this module, sees four fields repeated seven times, and
> "improves" it.

They are not being careless. They are applying a good instinct to code whose reasoning is invisible.
The nesting cost lives in the JSON, not in the Java, so nothing in the source suggests the
duplication was considered and chosen. Without a comment, the argument has to be rediscovered — and
it usually is, after the change ships and a consumer's `jq` breaks.

This is why the project asks for `TRADEOFF:` comments wherever a decision has a real alternative.
The distinction the constraint draws:

- A **WHAT** comment restates the code. `// four envelope fields`. Delete it, lose nothing.
- A **WHY** comment explains the code's existence.
- A **TRADEOFF** comment explains the code's *non-existence* — what is deliberately absent, and what
  it would have cost.

Only the last one survives a reader who disagrees with you, because it engages the objection they
are about to raise. Notice that the comment names the wrapper by its actual signature; a reader can
tell whether the alternative that was weighed is the same one they are imagining.

---

## Naming the conditions for revisiting

The comment closes with when the decision would flip:

> It stops being the right call if the envelope grows several more fields or the message count grows
> well past seven.

This matters. A tradeoff comment that only defends the current choice reads as a prohibition, and
prohibitions with no stated conditions get either obeyed forever or ignored entirely.

Both thresholds are real. At four fields and seven messages, the duplication is 24 lines. At eight
fields and thirty messages it is 240, and the wrapper's fixed nesting cost is amortised over far
more code. The decision is a function of scale, and saying so lets a future reader evaluate it
against their situation instead of guessing at yours.

---

## Try it yourself

```bash
./mvnw -pl common-events clean test
```

**Expect**: `Tests run: 31, Failures: 0` — unchanged, since this task added only a comment.

To see the cost the comment is describing, compare what a consumer would write:

```bash
jshell --class-path common-events/target/classes:$(./mvnw -q -pl common-events dependency:build-classpath -Dmdep.outputFile=/dev/stdout)
```

Then look at the flat JSON one of the round-trip tests produces, and mentally add `"payload": { ... }`
around the business fields. Every access path in every consumer, every schema, and every `jq`
expression in every runbook grows by one hop. That is what 24 lines of duplication bought.

---

## What comes next

**T028** — the last task of User Story 1: verify no framework leaked into the module. `dependency:tree`
should show `jackson-annotations` at compile scope and nothing else, proving **FR-010** holds by
construction rather than by intention.
