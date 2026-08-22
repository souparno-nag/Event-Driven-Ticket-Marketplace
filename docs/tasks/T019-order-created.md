# T019 — `OrderCreated`, the first message

**What this task did:** created `OrderCreated.java`, the record that starts every saga. It is the
first of the seven, so this document covers how a record with a compact constructor actually works;
the remaining six will assume it.

---

## The whole file

```java
public record OrderCreated(
        UUID messageId, UUID sagaId, Instant occurredAt, int schemaVersion,
        UUID orderId, UUID userId, UUID showId, List<String> seatIds, BigDecimal amount)
        implements SagaEvent {

    public OrderCreated {
        requireNonNull(messageId, "messageId");
        requireSagaMatchesOrder(sagaId, orderId);
        requireNonNull(occurredAt, "occurredAt");
        requireSchemaVersion(schemaVersion);
        requireNonNull(userId, "userId");
        requireNonNull(showId, "showId");

        seatIds = requireNonEmptyDistinctSeats(seatIds);
        amount = requireMoney(amount, "amount");
    }
}
```

That is the entire type. Fourteen lines of substance, and it gives you a constructor, nine
accessors, `equals`, `hashCode`, and `toString`.

## What a record generates for you

Written as an ordinary class, the same thing would be roughly 120 lines: nine private final fields,
a constructor assigning all nine, nine getters, an `equals` comparing all nine, a `hashCode`
combining all nine, and a `toString`. Every one of those is mechanical, and every one is a place to
make a mistake — the classic being an `equals` that forgets the field somebody added last month.

The record generates them from the component list. Three consequences matter here:

- **`equals` covers every component, always.** This is what makes T015's single
  `assertThat(restored).isEqualTo(original)` a complete round-trip assertion, and what keeps it
  complete when a component is added.
- **Fields are final and there are no setters.** Immutability by construction, which is **FR-005**.
- **Jackson understands records natively** (since 2.12). It reads the component names to map JSON
  keys, and calls the canonical constructor to build one. No annotations needed — which is why this
  module depends only on `jackson-annotations` and uses none of them.

Records also cannot extend a class. That is not a limitation being worked around here; it is the
reason research decision R2 settled on repeating the envelope rather than inheriting it, since
inheritance was never on the table.

## The compact constructor

This is the piece with unusual syntax:

```java
public OrderCreated {          // no parameter list, no braces around a signature
    ...
}
```

Java fills in the parameters implicitly, runs your code, and *then* assigns each parameter to its
field. Two things follow:

**You cannot assign to a field.** `this.seatIds = ...` is a compile error. The assignment is Java's
job, after your block ends.

**Reassigning a parameter changes what gets stored.** That is not a quirk to be tolerated, it is the
mechanism:

```java
seatIds = requireNonEmptyDistinctSeats(seatIds);
```

The caller passed some `List`. The helper validates it and returns an unmodifiable copy. That copy
is the parameter now, so that copy is what the field holds. Without the reassignment the record
would store the caller's own list — and the caller could keep adding to it after publishing.

This is why several of T014's helpers return a value instead of only throwing. `requireMoney` returns
the amount for symmetry, even though `BigDecimal` is already immutable and nothing needs replacing;
uniform shape at the call site is worth more than saving a return statement.

---

## The order the checks run in

```java
requireNonNull(messageId, "messageId");
requireSagaMatchesOrder(sagaId, orderId);      // ← also null-checks both
requireNonNull(occurredAt, "occurredAt");
requireSchemaVersion(schemaVersion);
```

Envelope first, then business fields, matching the component order. `sagaId` and `orderId` are not
null-checked separately because `requireSagaMatchesOrder` does it — checking them again would be
noise, and noise in a validation block is how a real check gets lost.

The order also determines **which error you see first** when a caller gets several things wrong at
once. Envelope problems surface before business ones, which is the right priority: a message with no
`messageId` is broken at a more fundamental level than one with a duplicate seat.

---

## The nine components

The first four are the envelope from T012, identical on all seven message types. The other five are
what makes this message specifically an order:

| Component | Note |
|---|---|
| `orderId` | The order. Equal to `sagaId`, and checked to be. |
| `userId` | Who is buying. Carried so consumers need not call back to order-service. |
| `showId` | **The concert.** The field whose name collision with `messageId` FR-003 exists to fix. |
| `seatIds` | The requested seats. Non-empty, distinct, all-or-nothing. |
| `amount` | The total. Non-negative, scale exactly 2. |

Two are worth dwelling on.

### `showId` sits next to `messageId`, deliberately

Both are `UUID`. Both are on this record. In the original brief both were called `eventId`, which
meant a call site could pass either where the other was meant and compile cleanly.

`OrderCreated` is the only message carrying both, so it is exactly where that bug would have lived —
and it is why T018's naming test asserts specifically that this record has two distinct components
named `messageId` and `showId`.

### `userId` is carried, not looked up

Inventory-service could fetch the user from order-service given the `orderId`. Carrying it in the
message means it does not have to.

That is the general shape of event-driven design: a message contains what its consumers need, so a
consumer can act on it without calling anyone. The moment a consumer must call back to the producer
to interpret a message, you have rebuilt a synchronous dependency inside an asynchronous system —
with all the coupling and none of the simplicity.

The cost is duplication: `userId` now exists in order-service's database *and* in every copy of this
message. That is accepted deliberately. A message is a **historical record** of a fact, not a
pointer to current state, and it should still make sense when read from a channel a week later, long
after the row it came from has changed.

---

## What is not here

**No `topic` field.** Where the message travels is `Topics.ORDER_CREATED`, and belongs to the
publisher.

**No behaviour.** No `confirm()`, no `totalWithFees()`. The record is a fact on a wire; logic
belongs in the service that acts on it. A method here would be shared by every service, which sounds
like reuse and is actually a coupling — the seven services could no longer disagree about what the
fact means for them.

**No `@JsonProperty` annotations.** Jackson maps by component name, and the names are already the
contract. Annotations would add a second place for the wire format to be defined, and therefore a
place for the two to disagree.

---

## Try it yourself

Main sources compile — the tests do not yet, since six records are still missing:

```bash
./mvnw -pl common-events compile        # BUILD SUCCESS, 7 source files
./mvnw -pl common-events test-compile   # still red: 6 records to go
```

I ran the first after writing the file. Watch the record's generated members:

```bash
javap -p common-events/target/classes/com/marketplace/events/OrderCreated.class
```

**Expect**: nine accessor methods named exactly like the components, plus `equals`, `hashCode`,
`toString`, and a constructor taking all nine — none of which appear in the source.

---

## What comes next

**T020** — `SeatsReserved`, the only record with a rule of its own: `lockExpiresAt` must be strictly
after `occurredAt`. It is the fencing field that stops a stalled saga confirming a seat somebody else
now holds.
