# T014 — `Validation`, the rules a message cannot escape

**What this task did:** created `Validation.java` — five helper methods holding the rules every
message checks in its constructor. This completes Phase 2; the seven message types in Phase 3 are
built out of the vocabulary these last seven files provide.

---

## The idea: make invalid states unrepresentable

There are two places you can put a rule like "a seat list must not be empty".

**Option A — check on receipt.** Every consumer, on getting a message, checks it:

```java
public void onOrderCreated(OrderCreated msg) {
    if (msg.seatIds().isEmpty()) { /* now what? */ }
    if (msg.amount().signum() < 0) { /* ... */ }
    ...
}
```

**Option B — check on construction.** The message cannot be built wrong in the first place:

```java
public record OrderCreated(..., List<String> seatIds, BigDecimal amount) implements SagaEvent {
    public OrderCreated {                              // ← compact constructor
        seatIds = requireNonEmptyDistinctSeats(seatIds);
        amount = requireMoney(amount, "amount");
    }
}
```

Option B is what this project does, and the difference is larger than it looks.

Under Option A, the checks live in seven consumers, and correctness depends on all seven remembering
to write them — and on the eighth consumer, added next year by someone who has not read this
document, remembering too. Worse, there is no good answer to "now what?": the message already
exists, it is already on the channel, and the consumer is left inventing a policy at the worst
possible moment.

Under Option B, a consumer holding an `OrderCreated` **knows** its seat list is non-empty and
duplicate-free, because there is no way to have constructed one otherwise. The check happens once,
at the earliest point where the mistake is still cheap, and every consumer downstream gets the
guarantee for free.

The general form of this idea is worth carrying around: **make invalid states unrepresentable.**
When a rule is enforced by the type, it stops being something people have to remember.

---

## What a compact constructor is

Records normally generate their constructor for you. A **compact constructor** lets you interpose
without writing the whole thing:

```java
public record OrderCreated(UUID messageId, List<String> seatIds, BigDecimal amount) {
    public OrderCreated {                 // no parameter list, no assignments
        seatIds = requireNonEmptyDistinctSeats(seatIds);
    }
}
```

Two things are unusual and both matter:

- **No parameter list and no assignments.** The parameters are implicit, and Java assigns them to
  the fields for you *after* your code runs.
- **Reassigning a parameter is meaningful.** `seatIds = ...` changes what gets stored. That is how a
  defensive copy gets substituted for the caller's list — and it is why several of these helpers
  return a value rather than just throwing.

This is the one place in a record where you can intervene, which is exactly why the validation goes
here.

---

## The five rules

### 1. `requireNonNull` — nothing is optional

Every `UUID`, `Instant`, and enum component must be present. There are no optional fields in these
contracts; a message with a null `orderId` is not a partial message, it is a broken one.

The method wraps the JDK's `Objects.requireNonNull` for one reason: **the error message**. The
JDK's version with no name throws a bare `NullPointerException`, and in a seven-component record
that leaves you guessing which component was null. Passing the name gives you
`messageId must not be null`, which is the difference between a two-second fix and a debugging
session.

### 2. `requireSagaMatchesOrder` — the identifiers must agree

`sagaId` must equal `orderId`. Since they are always the same, checking looks like ceremony. It is
not, and the reason is a failure that produces no error anywhere.

Recall from T012 that `sagaId` is the **partition key** — Kafka hashes it to decide which partition
a message goes to, and ordering is only guaranteed within a partition. If a message carried a
`sagaId` that did not match its order, it would hash to a *different* partition from the rest of its
saga. It would then arrive out of order relative to its own conversation — a payment result landing
before the reservation result that caused it.

Nothing would report a problem. Kafka delivered every message; the consumer processed every message.
The saga would simply behave incorrectly, occasionally, under load. One comparison at construction
eliminates the entire class of bug.

### 3. `requireNonEmptyDistinctSeats` — the busiest rule

Three jobs, and each one is a separate lesson.

**Non-empty.** An order for zero seats is meaningless. Rejecting it costs one line.

**No duplicates**, and note that it *rejects* rather than silently deduplicating:

```java
List.of("A1", "A1")   →  IllegalArgumentException
```

Collapsing to `["A1"]` would be friendlier and much worse. The caller asked for two seats and
presumably computed a price for two. Quietly reserving one produces an order charged for two seats
that holds one — a customer arriving at a venue with a ticket that does not exist. When input is
contradictory, failing loudly beats guessing which half the caller meant.

**A defensive copy**, which is the subtle one:

```java
return List.copyOf(seatIds);
```

Records are immutable — but only for the *reference*. If a record stored the caller's `ArrayList`
directly, the caller still holds a reference to that same list:

```java
var seats = new ArrayList<>(List.of("A1"));
var event = new OrderCreated(..., seats, ...);
seats.add("B7");                    // the "immutable" event just changed
```

The record's field cannot be reassigned, but the object it points to can be mutated by anyone
holding the other reference. `List.copyOf` severs that link and returns an unmodifiable list, so
mutation is impossible from either side. That is what makes **FR-005**'s immutability a fact rather
than a convention.

This mistake is common enough to have a name — *escaping references* — and it is especially nasty
here, because a message being altered after it was published is a bug that reproduces once a week
and never in a test.

### 4. `requireMoney` — the scale rule, which is not what it looks like

Two checks: non-negative, and **scale exactly 2**. Zero is allowed (a free ticket is a real order);
negative is not (that is a refund wearing an order's clothes, and refunds are not part of this saga).

The scale check is the interesting one. Why insist on `49.99` and reject `49.9`, when both are
perfectly good amounts?

Because of how `BigDecimal` defines equality:

```java
new BigDecimal("2.5").equals(new BigDecimal("2.50"))      // false !
new BigDecimal("2.5").compareTo(new BigDecimal("2.50"))   // 0 — numerically equal
```

`equals` compares **scale as well as value**. `2.5` and `2.50` are the same number and different
objects.

Now recall that records derive their `equals` from their components. So a message built with
`2.5`, serialized, and read back could come out unequal to itself — and **FR-006** requires exactly
that round-trip equality to hold. You would be staring at two amounts that print identically,
failing an assertion, with nothing looking wrong. That is a bad afternoon.

Pinning the scale at construction makes the representation **canonical**: there is one and only one
way to express a given amount, so equality means what you expect. The payment simulation, which
reads the last digit of the amount, gets an unambiguous answer for the same reason.

(This is also why `compareTo` is the right tool for comparing `BigDecimal` values in general, and
`equals` almost never is. Worth remembering outside this project.)

### 5. `requireSchemaVersion` — why the floor is 1, not 0

The version must be at least 1. The reason it does not start at zero is that **`0` is what an
uninitialised `int` already is.** If zero were legal, "the producer forgot to set a version" and
"the producer meant version zero" would be indistinguishable. Starting at 1 turns that mistake into
a rejected construction instead of a message nobody can interpret.

The same instinct applies whenever a default value could be confused with a real one.

---

## Two design choices in the file

**Package-private, not public.** The class and all five methods are visible only inside
`com.marketplace.events`. These are the contract module's own invariants, not a validation library
for the wider project. If they were public, a service would eventually call `requireMoney` on its
own domain type — and now these rules cannot change without breaking code they were never written
for. Keeping them package-private keeps them changeable.

**`NullPointerException` for nulls, `IllegalArgumentException` for everything else.** A reasonable
person might prefer one exception type throughout. This follows the JDK's own convention instead —
`Objects.requireNonNull` throws NPE, argument violations throw IAE — because a stack trace should
read the way a Java developer already expects. Being locally consistent while being globally unusual
is a poor trade.

---

## Verifying it

I ran a throwaway harness over all five helpers rather than assume the logic was right. Every case
behaved as intended:

```
ACCEPTED  sagaId == orderId
REJECTED  sagaId != orderId       -> IllegalArgumentException: sagaId must equal orderId, but ...
ACCEPTED  seats [A1, A2]
REJECTED  seats []                -> IllegalArgumentException: seatIds must not be empty
REJECTED  seats [A1, A1]          -> IllegalArgumentException: seatIds must not contain duplicates: A1
REJECTED  seats [A1, null]        -> NullPointerException: seatId must not be null
ACCEPTED  money 49.99
ACCEPTED  money 0.00
REJECTED  money 49.9 (scale 1)    -> IllegalArgumentException: amount must have scale exactly 2, ...
REJECTED  money 49.990 (scale 3)  -> IllegalArgumentException: amount must have scale exactly 2, ...
REJECTED  money -1.00             -> IllegalArgumentException: amount must not be negative, ...
ACCEPTED  schemaVersion 1
REJECTED  schemaVersion 0         -> IllegalArgumentException: schemaVersion must be at least 1, ...
REJECTED  null messageId          -> NullPointerException: messageId must not be null
REJECTED  mutating the returned list -> UnsupportedOperationException
```

Note the last line: the list handed back really is unmodifiable, so the defensive copy holds from
both directions.

That harness was scratch, not a test. The real tests arrive as **T017** (`ValidationTest`), which
asserts each of these rejections as a proper JUnit case — because a check that is not in the build
is a check that stops being true the moment someone edits the file.

---

## Try it yourself

```bash
./mvnw -pl common-events clean test-compile
```

**Expect**: `BUILD SUCCESS`, `Compiling 6 source files`.

The `BigDecimal` equality trap is worth feeling once, in `jshell`:

```bash
jshell
```

```java
import java.math.BigDecimal;
new BigDecimal("2.5").equals(new BigDecimal("2.50"))
new BigDecimal("2.5").compareTo(new BigDecimal("2.50"))
new BigDecimal("2.50").scale()
```

**Expect**: `false`, then `0`, then `2`. Same number, different objects — which is the entire reason
the scale rule exists.

And the escaping-reference problem, which is easier to believe after seeing it:

```java
import java.util.*;
record Holder(List<String> items) {}
var mutable = new ArrayList<>(List.of("A1"));
var h = new Holder(mutable);
mutable.add("B7");
h                                     // the "immutable" record now holds two items

record Safe(List<String> items) { Safe { items = List.copyOf(items); } }
var m2 = new ArrayList<>(List.of("A1"));
var s = new Safe(m2);
m2.add("B7");
s                                     // still one item — the copy severed the link
```

**Expect**: `Holder[items=[A1, B7]]` and `Safe[items=[A1]]`. That one-line compact constructor is
the whole difference.

Type `/exit` to leave.

---

## Phase 2 is complete

```
common-events/src/main/java/com/marketplace/events/
├── RejectionReason.java        T008    what went wrong with the seats
├── PaymentFailureReason.java   T009    what went wrong with the charge
├── CancellationReason.java     T010    why the order ended
├── Topics.java                 T011    where messages travel
├── SagaEvent.java              T012    what every message has (sealed in T026)
└── Validation.java             T014    what every message must satisfy

common-events/src/test/java/com/marketplace/events/
└── EventJson.java              T013    how messages are written and read
```

Every piece of shared vocabulary now exists. Nothing above is a message — they are the parts
messages are assembled from, which is exactly why the plan front-loads them: seven records written
against a settled vocabulary are seven straightforward files, while seven records invented alongside
their own vocabulary would each drift a little.

## What comes next

**Phase 3**, which is the MVP of this whole build step. It opens with tests rather than
implementation — T015 to T018 write the round-trip, validation, and naming-convention tests
*before* T019 to T025 write the seven records they exercise. Writing the test first forces you to
decide what "correct" means while it is still cheap to change your mind about it.
