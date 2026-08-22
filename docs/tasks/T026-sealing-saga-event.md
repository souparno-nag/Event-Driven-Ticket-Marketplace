# T026 — Sealing `SagaEvent`

**What this task did:** completed the declaration T012 had to leave open. `SagaEvent` is now a
**sealed** interface naming the seven records as its only permitted implementers.

---

## The one-line change

```java
public interface SagaEvent {                                    // before

public sealed interface SagaEvent                               // after
        permits OrderCreated, SeatsReserved, SeatsRejected,
                PaymentSucceeded, PaymentFailed, OrderConfirmed, OrderCancelled {
```

T012 could not write this, because Java requires every type in a `permits` clause to compile
alongside the interface and none of the seven existed yet. They do now, so the clause closes.

---

## What sealing buys: the compiler tracks your message types

Without sealing, anyone can implement the interface, so the compiler never knows the full set. Every
`switch` over messages needs a fallback:

```java
switch (event) {
    case OrderCreated e     -> handleCreated(e);
    case SeatsReserved e    -> handleReserved(e);
    ...
    default -> log.warn("unhandled message type");     // ← required, and a liability
}
```

With sealing, the set is known and the `default` disappears:

```java
switch (event) {
    case OrderCreated e     -> handleCreated(e);
    case SeatsReserved e    -> handleReserved(e);
    case SeatsRejected e    -> handleRejected(e);
    case PaymentSucceeded e -> handlePaid(e);
    case PaymentFailed e    -> handlePaymentFailed(e);
    case OrderConfirmed e   -> handleConfirmed(e);
    case OrderCancelled e   -> handleCancelled(e);
}                                    // exhaustive — the compiler checked
```

The value is not tidiness, it is **what happens on the day someone adds an eighth message type**.

| | With `default` | Sealed, no `default` |
|---|---|---|
| Adding a message type | Compiles fine | **Every incomplete `switch` fails to compile** |
| Missing case shows up | At runtime, as a log line | At build time, naming the type |
| Consequence in this system | A saga stalls; nothing says why | You fix it before merging |

That second row is the whole argument. A saga message that no consumer handles does not throw — it
is simply ignored, and an order sits in `PENDING` forever with every service reporting healthy. That
is the hardest class of bug in this project to notice, and sealing converts it into a compile error.

It is worth appreciating how unusual this is. These messages arrive as **bytes off a network**; the
compiler normally cannot help you at all with what a remote system might send. Sealing recovers a
slice of compile-time safety on the consuming side, where the possible shapes are known.

---

## The tradeoff, stated honestly

Sealing means the contract module holds a **closed** list. Adding an eighth message type touches
`SagaEvent` and every exhaustive `switch` in every service.

That is a cost, and for a plugin API or a library with external implementers it would be the wrong
choice — you would be forbidding the extension your users came for. Here it is exactly right,
because the seven message types *are* the contract. There is no legitimate eighth implementer
somebody else should be adding without a contract change. The compiler enforcing that is the
feature, not a restriction to work around.

The rule of thumb: seal when the set of subtypes is a **design decision you own**; leave open when it
is an **extension point you are offering**.

---

## Verification

The compiler records the permitted set in the class file itself:

```bash
javap -v common-events/target/classes/com/marketplace/events/SagaEvent.class | grep -A9 PermittedSubclasses
```

```
PermittedSubclasses:
  com/marketplace/events/OrderCreated
  com/marketplace/events/SeatsReserved
  com/marketplace/events/SeatsRejected
  com/marketplace/events/PaymentSucceeded
  com/marketplace/events/PaymentFailed
  com/marketplace/events/OrderConfirmed
  com/marketplace/events/OrderCancelled
```

`PermittedSubclasses` is a real JVM attribute, not a compile-time-only hint — the runtime enforces
it too, so a class generated at runtime cannot sneak in either.

Then, rather than trust that, I checked the seal actually rejects something. A throwaway record
implementing `SagaEvent` without being listed:

```
[ERROR] RogueEvent.java:[3,1] class is not allowed to extend sealed class:
        com.marketplace.events.SagaEvent (as it is not listed in its 'permits' clause)
```

Deleted immediately; the build is green again. Same habit as the mutation checks in T025 — confirm
the guard fires before believing it.

Full suite after sealing: **31 tests, 0 failures.** Sealing changed no behaviour, which is expected;
it changed what the compiler will permit next time.

---

## A subtlety: `permits` and T018's scan check different things

Now that the seven are named in one place, T018's reflective scan might look redundant. It is not,
and the difference is worth seeing.

- **`permits`** guarantees nobody *outside* the list implements `SagaEvent`.
- **T018's scan** walks every record compiled into the package, whether it implements `SagaEvent` or
  not.

So a new record added to the package that simply does not implement the interface would sail past
the permits clause — and T018 would still catch an `eventId` component on it. Two mechanisms with
overlapping but not identical coverage. Neither makes the other pointless.

---

## Try it yourself

```bash
./mvnw -pl common-events clean test
```

**Expect**: `Tests run: 31, Failures: 0`.

The interesting experiment is the one above — try to implement the interface without permission:

```bash
cat > common-events/src/main/java/com/marketplace/events/Rogue.java <<'JAVA'
package com.marketplace.events;
import java.time.Instant; import java.util.UUID;
record Rogue(UUID messageId, UUID sagaId, Instant occurredAt, int schemaVersion) implements SagaEvent {}
JAVA
./mvnw -pl common-events compile
rm common-events/src/main/java/com/marketplace/events/Rogue.java
```

**Expect**: `class is not allowed to extend sealed class ... (as it is not listed in its 'permits'
clause)`.

---

## What comes next

**T027** adds a `TRADEOFF:` comment to this same file, recording why the envelope is repeated across
all seven records rather than extracted into a wrapper — the decision the seven records embody but
do not currently explain.
