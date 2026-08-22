# T023 — `PaymentFailed`, the compensation trigger

**What this task did:** created `PaymentFailed.java`, the message that sends the saga backwards.

---

## The first message with real work to undo

Every failure so far has been cheap. `SeatsRejected` ends a saga in which nothing happened.

This one is different: by the time payment runs, **seats are held**. Somebody's inventory is locked
up. So this message does not merely end the saga, it starts the *reverse* of it:

```
OrderCreated ──► SeatsReserved ──► PaymentFailed ──► OrderCancelled ──► holds released
                       │                                                      ▲
                       └──────────────── the action being undone ─────────────┘
```

That reverse pass is **compensation**, and it is the defining feature of a saga. A database
transaction rolls back because one system owns everything involved. A saga spans services with
separate databases, so there is no shared rollback — instead each step that did something publishes
a fact, and another service reacts by undoing its own part.

Note that inventory-service does not release the seats because it saw `PaymentFailed`. It releases
them when it sees `OrderCancelled`. The order service owns the decision that the order is over;
inventory reacts to that decision. Keeping the chain going through the aggregate's owner rather than
letting every service interpret every failure is what stops the compensation logic from being
duplicated in five places.

---

## What this record deliberately omits

**No amount.** `PaymentSucceeded` carries one; this does not.

The reason is precise: on a decline, nothing was taken, so there is no amount to report. On a
**timeout**, nobody knows what was taken — that is the whole meaning of `TIMEOUT` from T009. Putting
a number here would assert a fact the publisher does not possess.

The figure at stake is already on `OrderCreated`, where it means "what the customer was asked to
pay". That is a request, not a claim about money that moved. A message should carry what its
publisher actually knows, and no more.

**No `paymentId`.** There may not be a charge to name. On a timeout there may be a charge that
nobody can identify — which is precisely why that case cannot be retried blindly and must be
reconciled out of band.

**No retry advice.** Whether to retry is the consumer's policy, decided from the reason.

---

## The reason field is doing the real work

```java
PaymentFailureReason reason   // DECLINED | TIMEOUT | PROVIDER_ERROR
```

T009 covers these in full. The short version is that they answer one question — *is it certain no
money moved?* — and the answer changes what happens outside the saga:

| Reason | Money moved? | What it implies |
|---|---|---|
| `DECLINED` | Certainly not | Clean cancel. Customer retries with another card. |
| `TIMEOUT` | **Unknown** | Cancel, but reconcile: a charge may exist with no order. |
| `PROVIDER_ERROR` | Almost certainly not | Cancel, and treat as a system signal — this is what a circuit breaker acts on. |

The saga does the same thing in all three cases: cancel and release. The reason matters for
everything *around* the saga — the message shown to the customer, the alert an operator sees, the
reconciliation job that hunts for charges with no order behind them.

That is why the reason survives here but is flattened to a single `PAYMENT_FAILED` on
`OrderCancelled` (T025). Detail is preserved where it is actionable and dropped where it is not.

---

## Why cancel at all on a timeout

It is worth questioning. If we do not know whether the charge went through, why release the seats
rather than wait?

Because waiting has no natural end. The provider may never answer. Meanwhile the seats are held, the
customer is staring at a spinner, and the hold is expiring anyway — so "wait" quietly becomes
"cancel later, having also lost the seats to expiry with no record of a decision".

Releasing them is the choice that keeps the system in a defined state. If money really was captured,
that becomes a refund — an unhappy outcome, but a *known* one that a reconciliation process can find
and resolve. A stranded saga with no terminal state is worse than a refund, because nothing goes
looking for it.

The general principle: when you cannot know the truth, prefer the outcome that leaves an
explainable, discoverable state over the one that leaves things ambiguous indefinitely.

---

## Try it yourself

```bash
./mvnw -pl common-events compile
```

**Expect**: `BUILD SUCCESS`, 11 source files. Two records left — and the test files start compiling
after the last one.

---

## What comes next

**T024** and **T025** — `OrderConfirmed` and `OrderCancelled`, the saga's two terminal messages. They
are the shortest records of the seven, and the pair is worth reading together, since which one gets
published is the entire outcome of the saga.
