# T012 — `SagaEvent`, the shared interface

**What this task did:** created `SagaEvent.java` — an interface declaring the four fields every
saga message carries. It also hit a Java constraint worth understanding, so the file is
*deliberately* not finished; the last piece lands in T026.

---

## The envelope

Every one of the seven messages carries the same four fields, whatever else it holds:

| Field | Type | What it is |
|---|---|---|
| `messageId` | `UUID` | Identity of **this message**. Never reused. |
| `sagaId` | `UUID` | Ties together every message of one order. Equals that order's `orderId`. |
| `occurredAt` | `Instant` | When the fact happened. |
| `schemaVersion` | `int` | Which version of this message's shape it is. |

Collectively that is the **envelope** — by analogy with a paper envelope, which carries addressing
and postmark while the letter inside carries the actual news. `SagaEvent` declares accessors for
those four and nothing else. No state, no behaviour, no default methods.

Each field earns its place for a reason that is not obvious on first reading, so:

### `messageId` — the idempotency key

Kafka guarantees **at-least-once** delivery. Not exactly-once. If a consumer processes a message
and then dies before recording that it did, the message is delivered again on restart. This is
normal operation, not an error.

Which means a consumer that naively acts on every delivery will eventually charge a card twice.

The fix is to make processing **idempotent** — safe to do more than once. Each consumer records the
`messageId`s it has already handled and skips repeats. That is the `processed_events` table the
project brief calls for, arriving in build step 2. At-least-once delivery plus idempotent consumers
gives you effectively-once processing, which is how real systems get there; exactly-once delivery is
mostly a thing people wish existed.

For this to work the id must be unique *per message*, never per fact. If a publish fails and is
retried, the retry is a **new message** with a new id, because a consumer needs to distinguish "the
same delivery again" from "a genuinely new fact".

### The `messageId` / `showId` naming, which is a fixed bug

The original project brief used `eventId` for two entirely different things: the identity of a
message, and the identity of the concert being ticketed. Both are `UUID`, so this compiles fine:

```java
new SeatsReserved(eventId, ...)   // which eventId? nobody knows
```

Two unrelated identifiers of the same type, sharing a name, passed as positional arguments. That is
a bug waiting for a tired afternoon. The contracts resolve it: `messageId` for the message,
`showId` for the concert, and the word "event" is banned as a field name anywhere in this module
(**FR-003**). Task T018 later adds a test that reflectively enforces the ban, so it cannot creep
back.

Worth noticing as a general lesson: the fix here was **naming**, not cleverness. A stronger fix
would be distinct types (`MessageId`, `ShowId`) so the compiler rejects a swap — that was judged
more ceremony than this project wants, but it is the direction if the problem recurred.

### `sagaId` — the partition key

It always equals `orderId`, which raises the reasonable question of why it exists separately.

It is the **partition key**. A Kafka topic is divided into partitions, and ordering is only
guaranteed within one. Kafka hashes the key to choose a partition, so keying by `sagaId` puts all
of one order's messages in the same partition, in order:

```
partition 0:  [order-A created] [order-A reserved] [order-A paid]     ← A's order preserved
partition 1:  [order-B created] [order-B rejected]
partition 2:  [order-C created] [order-C reserved] ...
```

Different orders land on different partitions and are processed concurrently. Same order, same
partition, strictly ordered. That is how you get parallelism *and* per-order ordering, and it is why
"a payment result can never overtake the reservation result before it" is a property of the design
rather than something consumers have to defend against. Task T048's test proves it holds under 100
concurrent orders.

The separate name is about *role*. `orderId` means "the order this concerns"; `sagaId` means "the
thing correlating this conversation". They coincide today, and naming both keeps the call site
honest about which one it is using.

### `occurredAt` — when the fact happened

Not when it was published, not when it was consumed. A message can sit in a channel through a
consumer outage and get handled ten minutes late; publish and consume times describe the plumbing,
not the business.

Anything reasoning about elapsed time needs the moment of the fact. The clearest case is build step
4's check of whether a seat hold has lapsed — comparing against a timestamp that really means "when
this message was finally read" would produce the wrong answer precisely when the system is
struggling, which is exactly when you need it right.

### `schemaVersion` — surviving your own deployments

The subtle one. Why carry a version number when every service is built from the same repository?

Because **during a rolling deployment they are not.** You deploy the new order-service; for a few
minutes some instances run the new build and some the old, and inventory-service has not been
updated at all. Producers and consumers on different builds is not an edge case, it is what every
deploy looks like from the inside.

So a consumer will sometimes meet a message shape it was not compiled against. The version lets it
*notice* instead of misreading the payload. And the rule when it does not recognise a version is
strict (**FR-023**):

> Do not process it. Route it to the dead-letter channel. **Do not discard it.**

The prohibition on discarding is the important half. These messages move money and seat inventory.
A dropped one leaves a saga stranded — an order stuck in `PENDING` forever, seats held by nobody,
and no record anywhere of what happened. Failing loudly and keeping the evidence beats a tidy log
with a hole in it.

---

## The interesting part: sealing, and why it is not done yet

The task asked for a **sealed** interface. Here is what that means, and why the file does not have
it yet.

### What sealing does

A normal interface can be implemented by anyone. A **sealed** interface names its permitted
implementers up front:

```java
public sealed interface SagaEvent
        permits OrderCreated, SeatsReserved, SeatsRejected,
                PaymentSucceeded, PaymentFailed, OrderConfirmed, OrderCancelled {
}
```

Now those seven are the only types that may implement it, and the compiler knows the complete list.
That unlocks **exhaustiveness checking**:

```java
switch (event) {
    case OrderCreated e      -> handleCreated(e);
    case SeatsReserved e     -> handleReserved(e);
    case SeatsRejected e     -> handleRejected(e);
    case PaymentSucceeded e  -> handlePaid(e);
    case PaymentFailed e     -> handlePaymentFailed(e);
    case OrderConfirmed e    -> handleConfirmed(e);
    case OrderCancelled e    -> handleCancelled(e);
}                                          // no `default` needed — and that is the point
```

No `default` branch is required, because the compiler can prove every case is covered. Which means
the day someone adds an eighth message type, **every `switch` like this becomes a compile error**
naming exactly what is unhandled.

Compare the unsealed version. You would need a `default` branch, and a new message type would slide
silently into it — probably logged, probably ignored, probably discovered by a customer. Sealing
converts "a message we forgot about" from a runtime surprise into a build failure. In a system
where the compiler cannot otherwise help you (messages arrive as bytes off a network), that is a
rare and valuable place to get compile-time safety.

### Why it cannot be written today

Java requires every type in a `permits` clause to exist and compile alongside the interface. The
seven records are Phase 3 — tasks T019 to T025. They do not exist yet.

Writing the sealed declaration now would fail with `cannot find symbol: OrderCreated`, and because
the module stops compiling, *nothing else* in it builds either. That would leave the repository
broken across several commits, which defeats the point of committing task by task: every commit
should build.

So the interface is **open for now** and gets sealed in **T026**, whose entire job is "wire the
seven records into the `SagaEvent` permits clause and confirm the sealed hierarchy compiles". The
plan already anticipated this two-step; the only thing this task adds is a comment in the file
saying so, with the final form written out, so nobody reading it in between wonders whether the
sealing was forgotten.

Being open in the meantime is strictly *weaker*, never *wrong*. The Phase 3 records implement the
interface either way. T026 only narrows who else is allowed to.

---

## What this interface deliberately does not do

It has no fields and no methods with bodies. It is a promise about shape, not shared machinery.

The tempting alternative is a wrapper generic over the payload:

```java
record Envelope<T>(UUID messageId, UUID sagaId, Instant occurredAt, int schemaVersion, T payload) {}
```

Write the envelope once, never repeat it. It was rejected (research decision **R2**) for a reason
visible only when you look at the wire format it produces:

```json
{ "messageId": "...", "sagaId": "...", "payload": { "orderId": "...", "seatIds": ["A12"] } }
```

Everything business-related is now one level down. Every consumer unwraps before reading anything.
Every JSON schema gains a nesting level. Every log query gets a `payload.` prefix. And schema
evolution becomes two questions instead of one — did the envelope change, or the payload?

Instead, the four fields are simply repeated on all seven records. That is duplication, and it is
the *right* duplication: the project's constitution explicitly prefers repeating something obvious
over introducing an abstraction to avoid it. Seven records × four fields is a cost you pay once
while typing; a nested wire format is a cost every consumer pays forever.

That reasoning gets written into the file itself as a `TRADEOFF:` comment in task **T027** — the
project asks that any decision with a real alternative records what was rejected and why, so the
next reader does not have to re-derive it.

---

## Try it yourself

```bash
./mvnw -pl common-events clean compile
```

**Expect**: `BUILD SUCCESS`, `Compiling 5 source files`.

You can watch the sealing constraint fail for yourself, which makes the reason concrete. Temporarily
edit the declaration in `SagaEvent.java` to:

```java
public sealed interface SagaEvent permits OrderCreated {
```

...and recompile. **Expect**: `cannot find symbol: class OrderCreated`. Revert it.

Then a smaller experiment, in `jshell`, showing that a sealed type must have permitted subtypes at
all:

```bash
jshell
```

```java
sealed interface Nothing {}
```

**Expect**: an error — `sealed class must have subclasses`. A sealed type with nobody permitted to
implement it is meaningless, so Java rejects it outright. That is the second reason the declaration
cannot simply be written early and filled in later.

Type `/exit` to leave.

---

## What comes next

**T013** — `EventJson.java`, a factory producing one correctly configured Jackson `ObjectMapper`.
There are four specific settings, and each one exists because its default is wrong for this
project. That file is where "why does my timestamp come back as a number?" gets answered.
