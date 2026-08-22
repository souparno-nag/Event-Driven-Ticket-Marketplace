# T015 — The round-trip test

**What this task did:** created `ContractRoundTripTest.java`, which asserts that every message
survives a trip through JSON and comes back equal to what went in.

**This test does not compile yet.** That is deliberate, and the first section explains why.

---

## The build is red on purpose

Phase 3 is ordered tests-first: T015–T018 write the tests, T019–T025 write the seven records they
exercise. So right now the test refers to `OrderCreated`, `SeatsReserved` and five other types that
do not exist. The module does not build.

That is the **red phase** of test-driven development, and the cycle is:

```
RED      write a test that fails (or does not compile)   ← we are here
GREEN    write the minimum code to make it pass
REFACTOR clean up, with the test holding you honest
```

Why go through a stage where the build is broken? Because of what writing the test first *forces*:

- **You decide what correct means before you decide how to achieve it.** Look at the test — it
  settles that `occurredAt` keeps nanoseconds, that `100.00` stays `100.00`, that seat order is
  preserved. Those are contract decisions. Made after the implementation, they tend to be discovered
  by reading the code and then written down as whatever the code already does, which is not a test,
  it is a description.
- **You use the API before committing to it.** This test is the first code to construct these
  records, and it exercises the component order the records will have.
- **You know the test can fail.** A test written after the code often passes on its first run and is
  never observed failing. That is a test you have no evidence works. This one is guaranteed to have
  been red first.

The build goes green at T025 when the last record lands. Until then `./mvnw test` reports compilation
errors naming exactly the types still to be written — which is a to-do list the compiler maintains
for you.

---

## What "round trip" means and why it is the first test

```
OrderCreated  ──serialize──►  JSON text  ──deserialize──►  OrderCreated
     └────────────────── must be equal ───────────────────────┘
```

This is **FR-006**, and it is the one property the whole system depends on without exception.

Consider what a violation looks like. Order-service publishes an order for seats `[A12, A13]` at
`49.99`. Inventory-service deserializes it and gets `[A12, A13]` at `49.9`. Nothing errors. The
consumer has no way to notice, because it never saw the original — the message *is* its only view of
reality. The saga proceeds confidently on corrupted data.

Every other property in this module is a refinement of this one. Hence it is written first.

---

## How the seven cases are covered without seven copies of the test

```java
@ParameterizedTest(name = "{0}")
@MethodSource("allSevenMessages")
void round_trips_to_an_equal_object(SagaEvent original) throws Exception {
    String json = mapper.writeValueAsString(original);
    SagaEvent restored = mapper.readValue(json, original.getClass());
    assertThat(restored).isEqualTo(original);
}
```

A **parameterized test** runs the same test body once per input. `@MethodSource` names a method
supplying those inputs — here, one populated instance of each of the seven types. Seven test cases,
one body, and adding an eighth message type later means adding one line to the source method.

Two details worth noticing:

**`original.getClass()`** is how one generic test body deserializes into the right concrete type. The
static type is `SagaEvent`, but the runtime class is `OrderCreated`, and that is what Jackson needs.

**`Named.of(...)`** labels each case with its type name, so a failure reports `SeatsReserved` rather
than a record's full `toString` with six UUIDs in it. Test output is read by someone trying to find
a problem quickly; making it legible is not decoration.

### Why one `isEqualTo` is enough

```java
assertThat(restored).isEqualTo(original);
```

That single line checks every component. Records generate `equals` from **all** of them, so nothing
can be forgotten — unlike a hand-written comparison, which checks the fields the author thought of
on the day. Add a component to a record later and this assertion covers it automatically.

This is a quiet argument for records over classes: `equals`, `hashCode`, and `toString` derived from
the actual shape mean tests like this stay complete for free.

---

## The four specific tests, and the bug each one catches

The parameterized test proves equality holds. These four assert *why* it holds, so that when it
breaks the failure names its own cause.

### Seat list contents

```java
assertThat(restored.seatIds()).containsExactly("A12", "A13", "B01");
```

`containsExactly` checks **contents and order**. A list that came back reordered, or as a set with
duplicates collapsed, would be a different bug from "the field is missing", and worth its own
failure message.

### Nanosecond precision

```java
assertThat(restored.occurredAt().getNano()).isEqualTo(123456789);
```

The test data uses `2026-08-22T09:15:30.123456789Z` — nine fractional digits — precisely because a
serializer that quietly truncates to milliseconds still produces an `Instant` that looks completely
normal. Asserting on the nano field turns a silent loss into a named failure.

This is the precision that research decision R3 warns about from the other direction: PostgreSQL
stores microseconds, so from build step 2 the same value will lose its last three digits crossing
the database. Different problem, same lesson — precision is lost quietly unless something asserts on
it.

### Money exactness

```java
assertThat(restored.amount()).isEqualTo(new BigDecimal("100.00"));
assertThat(json).contains("100.00").doesNotContain("1E+2");
```

Two assertions doing different jobs.

The first works *because* `BigDecimal.equals` compares scale — the behaviour that T014's validation
rule exists to tame. Here it is an asset: it fails if `100.00` returns as `100.0`, which is
numerically identical and contractually wrong.

The second asserts on the **JSON text**, confirming `WRITE_BIGDECIMAL_AS_PLAIN` from T013 is actually
in effect rather than merely configured.

### ISO-8601 on the wire

```java
assertThat(json).contains("\"occurredAt\":\"2026-08-22T09:15:30.123456789Z\"");
```

The only test here that asserts on the serialized text rather than the restored object, and the
reason is worth internalising: **the wire format is part of the contract.**

The round-trip assertions would still pass if timestamps became epoch numbers, since Java would
write and read them consistently. But anything outside Java reading these messages — a debugging
session with `kafka-console-consumer`, a script, a service in another language — depends on the
actual text. Pinning it means a future Jackson upgrade that changes a default breaks a test rather
than breaking a consumer nobody remembered.

---

## A small thing: fixed test data

```java
private static final UUID ORDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
```

Not `UUID.randomUUID()`. A failing test should be reproducible from the source alone; random inputs
turn "this test fails" into "this test failed that time", which is the worst kind of test to inherit.

The recognisable all-ones, all-twos pattern also makes output readable at a glance — you can see
which identifier is which without comparing thirty-six characters.

(Randomised inputs *are* valuable, but as a deliberate technique — property-based testing — where
the framework reports the exact seed that failed. Accidental randomness gives you the flakiness
without the benefit.)

---

## Try it yourself

The interesting thing to do right now is watch it fail:

```bash
./mvnw -pl common-events clean test-compile
```

**Expect**: `BUILD FAILURE`, with errors like:

```
[ERROR] .../ContractRoundTripTest.java:[52,17] cannot find symbol
  symbol:   class OrderCreated
  location: class com.marketplace.events.ContractRoundTripTest
```

Read that as a to-do list. Every missing symbol is a record still to be written, and the list gets
shorter with each of T019–T025 until it empties.

---

## What comes next

**T016** adds one more test to this same file: deserializing a message that carries a field the
consumer has never heard of, and asserting it is ignored rather than fatal. That is the property
that makes it possible to deploy a new producer without redeploying every consumer first.
