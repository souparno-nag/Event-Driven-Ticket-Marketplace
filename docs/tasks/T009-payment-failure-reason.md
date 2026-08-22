# T009 — The `PaymentFailureReason` enum

**What this task did:** created `PaymentFailureReason.java` — three values naming why a charge did
not succeed.

---

## How this differs from T008

`RejectionReason` (T008) describes a failure at the *first* step of the saga. Nothing had happened
yet, so a rejection simply ends the story: no seats held, no money moved, nothing to undo.

A payment failure is different, and the difference is the whole reason this step matters. By the
time payment runs, **seats are already held**. The saga has taken a real action in the world. So a
payment failure cannot just stop — it has to run *backwards*:

```
OrderCreated → SeatsReserved → PaymentFailed → OrderCancelled → holds released
                    ▲                                                  │
                    └──────────── the action being undone ─────────────┘
```

That undo step is called **compensation**, and it is the defining idea of a saga. A database
transaction can roll back because one system controls everything involved. A saga spans several
services with their own databases, so there is no shared rollback. Instead, each step that did
something ships an action that undoes it. `PaymentFailed` is the trigger for exactly that.

---

## The question these three values answer

You could imagine one value: `PAYMENT_FAILED`, done. The reason there are three comes down to a
single question the rest of the system needs answered:

> **Is it certain that no money moved?**

### `DECLINED` — yes, certainly none

The provider said no: insufficient funds, blocked card, failed fraud check. A decline is an
*answer*. Nothing was charged and nothing can be charged later on that attempt. Compensation
releases the seats and the matter is closed.

Retrying is pointless — the same card with the same balance declines again. The recovery is a human
one: a different card, which means a new order.

This is the value our simulated payment service produces. Per the project brief it declines any
amount ending in 7 and approves everything else, which is a cheap way to exercise the compensation
path on demand without signing up to a real payment provider.

### `TIMEOUT` — **unknown**, and that is the point

No response arrived before the deadline. Note carefully what this does *not* say: it does not say
the payment failed. It says **nobody knows**.

The request might have been lost on the way out. Or it might have been received, processed, and
charged — with only the *response* lost on the way back. From the caller's side those two look
identical.

This is one of the genuinely hard problems in distributed systems, and it has a name: you can never
be sure whether a message you sent was acted on. It leads directly to a rule worth memorising:

> **A timeout is not a failure. Retrying it can double-charge the customer.**

The safe way to retry is to send an **idempotency key** — a unique identifier attached to the
charge. The provider records it, so if the same key arrives twice it returns the original result
instead of charging again. "Idempotent" means *doing it twice has the same effect as doing it
once*, and it is the property that makes retries safe.

This is why the project's resilience rules say retries apply **only to idempotent operations**,
rather than blanket-retrying anything that failed. That constraint looks arbitrary until you have
seen a naive retry loop bill someone three times.

The saga still cancels on a timeout. Holding a customer's seats hostage to an unresolved charge is
worse than releasing them; if money really was captured, that becomes a refund handled outside the
saga.

### `PROVIDER_ERROR` — the payment system itself is unwell

A malformed response, an internal error, an unreachable endpoint. The customer's card was never the
problem.

The reason to separate it from `DECLINED`: **it says something about the system, not the order.**
Declines are routine — a steady trickle of them is a normal Saturday. A burst of provider errors is
an incident. It is the signal an operator watches, and the one a **circuit breaker** trips on: after
enough failures, stop calling the failing service for a while, fail fast, and let it recover instead
of piling on load. Resilience4j does that for us in a later build step, but only if the events tell
it apart from ordinary declines.

---

## The `TRADEOFF:` comment — what was left out

The obvious missing thing is the provider's own error message. `"card_declined: insufficient
funds"` would be handy when debugging at 3am. It was deliberately excluded, for two reasons worth
understanding:

1. **It is untrusted third-party text.** It would be copied into a message that gets stored,
   replayed, and possibly shown to a user. Text from an external system that flows into your data
   store and out to your UI is a category of problem you would rather not have.
2. **It would quietly become the real contract.** Someone would write
   `if (msg.contains("insufficient"))`, and now the saga depends on one provider's exact wording.
   Swapping providers then breaks logic in a service that has nothing to do with payments.

The detail still exists — in the payment service's own logs, which are tied back to this event by
the trace identifier travelling in the message *headers*. Note that: correlation data goes in
headers, business facts go in the body (**FR-024**). Keeping those separated is why the seven
contracts stay small and stable while the observability story can change freely.

---

## Try it yourself

```bash
./mvnw -pl common-events clean compile
```

**Expect**: `BUILD SUCCESS`, compiling 2 source files now.

There is a small thing worth seeing about how enums behave, using `jshell` — Java's interactive
shell — pointed at the compiled classes:

```bash
jshell --class-path common-events/target/classes
```

Then paste:

```java
import com.marketplace.events.PaymentFailureReason;
PaymentFailureReason.valueOf("TIMEOUT")
PaymentFailureReason.valueOf("timeout")
```

**Expect**: the first returns `TIMEOUT`. The second throws `IllegalArgumentException`.

That case sensitivity is not a detail — it is the contract being enforced. When Jackson reads
`{"reason": "TIMEOUT"}` off Kafka it calls `valueOf` underneath, so a producer that shipped
lowercase would fail loudly at the boundary rather than silently deserializing to something wrong.
Loud failure at a boundary is what you want; the alternative is a corrupt value travelling deep
into the system before anyone notices.

Type `/exit` to leave `jshell`.

---

## What comes next

**T010** — `CancellationReason`, the last of the three enums. It has an interesting wrinkle: one of
its values exists for a feature that has not been built yet.
