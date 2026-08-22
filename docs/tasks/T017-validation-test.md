# T017 — The validation test

**What this task did:** created `ValidationTest.java`, asserting that every way to construct an
invalid message is rejected. Fifteen cases across the six rules from `data-model.md`.

Still red, for the same reason as T015 and T016.

---

## Testing the wiring, not the helper

The obvious way to test T014's work is to call it directly:

```java
assertThatThrownBy(() -> Validation.requireMoney(new BigDecimal("49.9"), "amount"))
    .isInstanceOf(IllegalArgumentException.class);
```

This test does not do that. Every case goes through a **record constructor** instead:

```java
assertThatThrownBy(() -> new OrderCreated(
        MESSAGE_ID, ORDER_ID, OCCURRED_AT, VERSION, ORDER_ID, USER_ID, SHOW_ID, SEAT_IDS,
        new BigDecimal("49.9")))
    .isInstanceOf(IllegalArgumentException.class);
```

The reason is worth taking seriously, because it generalises to a lot of testing decisions.

`requireMoney` is nine lines of obvious code. It is not where the bug will be. The bug will be a
record whose compact constructor **forgot to call it** — one missing line in one of seven files, in
a project where each record was written in a separate task. A test aimed at the helper passes
happily while that record ships unvalidated.

So the property under test is not *"the helper works"*. It is **"the record uses the helper"**. Test
the behaviour you actually depend on, at the boundary you actually depend on it — not the
implementation detail that happens to be easiest to reach.

There is a cost: these tests will not compile until the records exist, whereas helper tests could
run today. That is the trade, and it is the right way round — a test that runs sooner but checks the
wrong thing is not a bargain.

---

## What `@Nested` is doing

The cases are grouped into inner classes, one per rule:

```java
@Nested
@DisplayName("rule 4 — money is non-negative with scale exactly 2")
class Money {
    @Test void rejects_a_negative_amount() { ... }
    @Test void rejects_an_amount_with_too_few_decimal_places() { ... }
    ...
}
```

JUnit 5 runs `@Nested` inner classes as sub-groups, so the output is a tree:

```
ValidationTest
├─ rule 1 — nothing is optional
│  ├─ ✔ rejects a null message id
│  └─ ✔ rejects a null occurred at
├─ rule 4 — money is non-negative with scale exactly 2
│  ├─ ✔ rejects a negative amount
│  └─ ✘ rejects an amount with too few decimal places
```

With fifteen flat test methods you get an alphabetical list and have to reconstruct what belongs
with what. Grouped, a failure immediately says *which rule* broke. `@DisplayName` supplies readable
labels, since method names cannot contain spaces or an em dash.

---

## The cases, and what each is really about

Most are direct. Four are worth a note.

### Two exception types, on purpose

```java
.isInstanceOf(NullPointerException.class)      // for nulls
.isInstanceOf(IllegalArgumentException.class)  // for everything else
```

This is the JDK convention T014 chose to follow — `Objects.requireNonNull` throws NPE, argument
violations throw IAE. The tests assert it explicitly so the convention is pinned rather than
incidental. If someone later "tidies" the helpers to throw one type everywhere, these fail and the
decision gets made deliberately rather than by accident.

Each also asserts on the message:

```java
.hasMessageContaining("messageId")
```

That is not decoration. A record has nine components; a bare `NullPointerException` tells you one of
them was null and leaves you to guess. Asserting the field is named keeps the diagnostics from
silently degrading — an error message is a feature, and untested features rot.

### The defensive-copy test asserts from two directions

```java
List<String> mutable = new ArrayList<>(List.of("A12"));
OrderCreated event = new OrderCreated(..., mutable, ...);

mutable.add("B07");

assertThat(event.seatIds()).containsExactly("A12");            // caller cannot reach in
assertThatThrownBy(() -> event.seatIds().add("C01"))           // consumer cannot reach in
    .isInstanceOf(UnsupportedOperationException.class);
```

Two distinct failures, and it is possible to fix one and still have the other:

- Storing the caller's list directly means the **caller** can change a published message afterwards.
  That is the escaping-reference bug from T014.
- Copying into a plain `ArrayList` fixes that but still hands **consumers** a mutable list they can
  modify.

`List.copyOf` closes both. Asserting both is what proves it, and this is the only test here that
checks a success path rather than a rejection — because immutability is not something you can
observe by trying to build something invalid.

### Zero is accepted, and that is a test too

```java
@DisplayName("accepts zero — a free ticket is a real order")
void accepts_a_zero_amount() {
    new OrderCreated(..., new BigDecimal("0.00"));
}
```

No assertion. The test passes if construction does not throw.

Its job is to pin a **boundary**. "Non-negative" and "positive" are one character apart in an
implementation and worlds apart in behaviour, and a suite that only tests rejections would pass
happily if someone tightened the rule to `signum() <= 0` and quietly broke every comped ticket.

Whenever a rule has an edge, test *both* sides of it. The rejections tell you what is forbidden; a
case like this tells you what is permitted, which is the half people forget to write down.

### `lockExpiresAt` must be *strictly* after

```java
void rejects_an_expiry_equal_to_occurred_at() { ... }
```

Two tests cover rule 6: expiry *before* `occurredAt`, and expiry *equal* to it. The second is the
interesting one.

A hold that expires at the exact instant it was taken is already expired. The step-4 fencing check —
"is `now` after `lockExpiresAt`?" — could never succeed for such a message. Requiring *strictly
after* rather than *not before* makes that state impossible to construct at all.

Off-by-one at a boundary is the most common way a rule is subtly wrong, and it is invisible unless a
test sits exactly on the edge. Note also that this is the one rule not in `Validation` — T020 puts
it in `SeatsReserved`'s own constructor, because it is the only record with two timestamps to
compare.

---

## Try it yourself

```bash
./mvnw -pl common-events clean test-compile
```

**Expect**: still `cannot find symbol` for the seven records — now reported from three test files
rather than one.

The `BigDecimal` scale behaviour the money cases rest on is worth confirming directly if you have
not already:

```bash
jshell
```

```java
import java.math.BigDecimal;
new BigDecimal("49.9").scale()
new BigDecimal("49.990").scale()
new BigDecimal("49.99").equals(new BigDecimal("49.990"))
```

**Expect**: `1`, `3`, and `false`. Three ways to write nearly the same amount, three different
objects — which is exactly what the scale rule exists to prevent from reaching the wire.

Type `/exit` to leave.

---

## What comes next

**T018** — `NamingConventionTest`, the last test before the records. It is unlike the others: rather
than exercising behaviour, it uses reflection to assert that no record component anywhere in the
package is named `eventId`, enforcing the naming fix from FR-003 mechanically so it cannot creep
back in a future edit.
