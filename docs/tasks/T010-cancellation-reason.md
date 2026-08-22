# T010 — The `CancellationReason` enum

**What this task did:** created `CancellationReason.java`, the third and last of the shared enums.

---

## Three enums, and why they are not one

With this file, the vocabulary looks like:

| Enum | On which message | Answers |
|---|---|---|
| `RejectionReason` | `SeatsRejected` | Why could the seats not be held? |
| `PaymentFailureReason` | `PaymentFailed` | Why did the charge not succeed? |
| `CancellationReason` | `OrderCancelled` | Why did this **order** end without tickets? |

A reasonable first instinct is to merge them — one big `FailureReason` with all the values. It was
rejected, and the reason is worth understanding because it generalises well beyond enums.

**The first two describe why a *step* failed. The third describes why the *order* ended.** Those are
different facts, owned by different services:

- `RejectionReason` is inventory's vocabulary. Inventory decides what counts as a rejection.
- `PaymentFailureReason` is the payment service's vocabulary.
- `CancellationReason` is the order service's vocabulary. It is the service that owns order state.

Merging them would mean the order service publishes values it does not own and cannot reason about,
and every time inventory invented a new rejection cause, the order contract would grow too — a
change rippling into services that never cared. Keeping each vocabulary with the service that owns
it is what stops that ripple.

Notice this also means detail is deliberately **lost** at the boundary. Every kind of payment
failure — declined, timed out, provider broken — collapses into the single value `PAYMENT_FAILED`
here. That is not sloppiness. The full detail still exists on the `PaymentFailed` message for
whoever decides about refunds. It simply does not matter to the seats, which get released
identically either way. Passing along detail that no consumer acts on is how contracts bloat.

---

## What `OrderCancelled` actually triggers

This message is the **compensation trigger**. When inventory receives it, it releases the seat
holds — regardless of which of the three reasons it carries.

```
                                   ┌─ SeatsRejected ──────────────┐
                                   │  (nothing held yet)          │
OrderCreated ──► inventory ────────┤                              ├──► OrderCancelled ──► release
                                   │                              │
                                   └─ SeatsReserved ──► payment ──┘
                                      (seats held!)     fails
```

Both paths end at `OrderCancelled`, but they are not equally interesting:

- Via `SeatsRejected` → reason `SEATS_UNAVAILABLE`. **Nothing to undo** — a rejection is
  all-or-nothing, so no hold was ever taken. The cancellation is pure bookkeeping: it closes the
  order. This is the shortest path through the saga.
- Via `PaymentFailed` → reason `PAYMENT_FAILED`. **Real work to undo** — seats are held and must be
  released, or they sit unsellable until their timer expires.

A useful habit: when reading a saga, ask of every failure path *what did the earlier steps already
do?* That is the list of things compensation must reverse.

---

## The interesting one: a value with no caller

`RESERVATION_EXPIRED` is emitted by nothing. No code produces it, and none will until build step 4.
The project's own rules say not to build things speculatively. So why is it here?

### The problem it is for

Seat holds expire. A hold is not forever — it lasts a couple of minutes, otherwise an abandoned
checkout would keep seats unsellable indefinitely.

Now imagine a saga that stalls. The payment service is slow, or a consumer was restarting, and four
minutes pass between the seats being held and the payment succeeding. Meanwhile the hold lapsed and
another customer legitimately bought those same seats.

If the original saga now confirms, **two people own seat A12.** For a ticketing system that is the
worst possible bug, and the whole load test in this project exists to prove it cannot happen.

The defence is a **fencing check**: before confirming, compare the hold's expiry time against now.

```java
if (Instant.now().isAfter(reserved.lockExpiresAt())) {
    // the hold lapsed — cancel, do not confirm
    cancel(CancellationReason.RESERVATION_EXPIRED);
}
```

"Fencing" is the general term for carrying a token or deadline that proves your claim is still
valid, so a slow actor cannot act on a stale one. The `lockExpiresAt` field on `SeatsReserved` is
the fence; this enum value is what the check produces when the fence has fallen.

### Why declare it *now*

Because **enum constant names are part of the wire format.** Jackson writes the constant name
straight into the JSON:

```json
{ "reason": "RESERVATION_EXPIRED" }
```

Adding a value later is therefore not a local edit. It produces messages that already-deployed
consumers cannot deserialize — they call `valueOf("RESERVATION_EXPIRED")` on a version of the enum
that has never heard of it, and throw. Fixing that means bumping the schema version and
redeploying every service that reads the channel, in the right order.

So the cost comparison is lopsided:

| | Cost |
|---|---|
| Declare it now | One unused constant. Nothing. |
| Add it in step 4 | Coordinated redeploy of every consumer of this channel. |

This is a specific and limited exception to "do not build speculatively", and the code says so in
its `TRADEOFF:` comment. It holds because the requirement is **confirmed**, not imagined — the
design documents already name the step-4 fencing check — and because deferring costs a *breaking
change* rather than an ordinary edit.

The reasoning does **not** transfer to speculative code. An unused abstract base class "for later"
is a liability you maintain and read past forever. A value in a published vocabulary is a different
kind of commitment: it costs one line, and it buys the ability to change your mind without
coordinating a fleet.

---

## Phase 2 so far

```
common-events/src/main/java/com/marketplace/events/
├── RejectionReason.java        ✅ T008
├── PaymentFailureReason.java   ✅ T009
└── CancellationReason.java     ✅ T010
```

Those were the three tasks marked `[P]` — parallel, because they touch different files and depend
on nothing incomplete. The rest of Phase 2 is sequential:

- **T011** `Topics.java` — the fourteen channel names as constants.
- **T012** `SagaEvent.java` — a *sealed* interface: an interface that names, up front, the only
  types allowed to implement it.
- **T013** `EventJson.java` — one correctly configured Jackson `ObjectMapper`.
- **T014** `Validation.java` — the shared rules the records enforce in their constructors.

Then Phase 3 builds the seven messages themselves out of this vocabulary.

---

## Try it yourself

```bash
./mvnw -pl common-events clean compile
```

**Expect**: `BUILD SUCCESS`, `Compiling 3 source files`.

Worth doing once, now that all three enums exist — confirm the module really is framework-free,
which is requirement **FR-010** and the reason this module can be imported by anything:

```bash
./mvnw -pl common-events dependency:tree
```

**Expect**: `com.fasterxml.jackson.core:jackson-annotations` at `compile` scope, and everything else
(`jackson-databind`, `junit-jupiter`, `assertj-core`) marked `test`. No `org.springframework`
anywhere at compile scope.

A dependency tree shows not only what you asked for but what those libraries dragged in with them —
**transitive** dependencies. Checking it occasionally is how you catch a single convenient-looking
import quietly pulling half a framework onto your classpath. Task T028 makes this an explicit
verification step later; running it now means no surprise then.
