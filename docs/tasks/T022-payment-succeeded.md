# T022 — `PaymentSucceeded`, and why the amount appears twice

**What this task did:** created `PaymentSucceeded.java`, the message that turns a pending order into
a confirmed one.

---

## The point of no return

```
OrderCreated ──► SeatsReserved ──► PaymentSucceeded ──► OrderConfirmed
```

Everything before this message is undoable. Seats can be released; an order can be cancelled with
nothing lost but a customer's time.

This message is where that stops being true. **Money has moved.** Anything that goes wrong after it
cannot be handled by the saga's compensation logic, because releasing a seat is a database write and
un-charging a card is not — it is a refund, which is a new transaction with its own settlement time,
its own failure modes, and usually a human deciding to issue it.

That asymmetry is a good thing to notice when designing any saga: order the steps so the irreversible
one comes **last**. Charging before holding the seats would mean every seat conflict produced a
refund. Holding first means the common failure costs nothing.

`paymentId` is the provider's identity for the charge. It is carried because a refund or a dispute
is raised against *that*, and reconstructing it later from an order id means asking the payment
provider, which is exactly the call you cannot make when the provider is the thing that is broken.

---

## The interesting bit: `amount` is here *and* on `OrderCreated`

The same figure appears on two messages. That looks like the kind of duplication you would normally
factor out — the consumer already knows the amount from `OrderCreated`, so why repeat it?

Because **they are not the same fact.**

| Message | `amount` means |
|---|---|
| `OrderCreated` | what the customer was **asked** to pay |
| `PaymentSucceeded` | what was **actually taken** |

Those should match. When they do not, you have a bug that matters more than most, and it is only
detectable because both numbers exist. Collapse them into one and the system loses the ability to
notice it charged the wrong amount — the discrepancy is not resolved, it becomes invisible.

This is the general reason financial systems repeat values that "should" be derivable: **the copy is
the audit trail.** A message is a historical record, and a record that stores only a reference tells
you what the referenced thing says *now*, not what it said at the time.

### The contract cannot enforce agreement

Worth being clear about a limit. `PaymentSucceeded` has no sight of the `OrderCreated` that preceded
it — a record validates itself, not its history. So nothing here can assert the two amounts match.

That check belongs to order-service, which holds the order row and can compare. The contract's job
is to make the comparison *possible* by carrying both figures. Knowing which invariants a type can
enforce and which it cannot is worth being explicit about, because the tempting move is to assume
"the contract validates it" and then find that nobody does.

---

## A word on the simulated payment service

There is no real provider in this project. Per the brief, payment-service declines any amount whose
value ends in 7 and approves everything else after a short delay.

That is a deliberately silly rule with a serious purpose: it makes the compensation path
**deterministic**. Testing failure handling against a real provider means either a sandbox that fails
on its own schedule or elaborate mocking. Here, an order for `£49.97` fails every single time, and
the k6 load test in build step 9 can assert exact counts of successes and rejections.

It also explains why T014 pins `BigDecimal` scale to exactly 2. "The last digit" is only unambiguous
if there is exactly one way to write the amount — `49.97` and `49.970` would otherwise be the same
money with different last digits.

---

## Try it yourself

```bash
./mvnw -pl common-events compile
```

**Expect**: `BUILD SUCCESS`, 10 source files. Three records to go.

The decline rule is a nice illustration of why the scale rule exists:

```bash
jshell
```

```java
import java.math.BigDecimal;
var a = new BigDecimal("49.97");
var b = new BigDecimal("49.970");
a.unscaledValue().toString().endsWith("7")
b.unscaledValue().toString().endsWith("7")
a.equals(b)
```

**Expect**: `true`, `false`, `false`. The same amount of money, two spellings, and a payment rule
that reads the last digit gives opposite answers for them. Pinning the scale is what removes the
ambiguity before it reaches the wire.

---

## What comes next

**T023** — `PaymentFailed`, which triggers the compensation path: real work has been done by now, and
this is the message that says undo it.
