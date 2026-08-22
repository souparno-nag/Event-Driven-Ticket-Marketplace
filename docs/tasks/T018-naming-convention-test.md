# T018 — The naming convention test

**What this task did:** created `NamingConventionTest.java`, which uses reflection to assert that no
record component in the contract module is named `eventId` — or mentions the word "event" at all.

This is the last of the four test tasks. The build stays red until T025.

---

## The bug being locked out

The original project brief used `eventId` for two entirely unrelated things:

- the identity of **a message**, and
- the identity of **the concert being ticketed**.

Both are `UUID`. So this compiles, runs, and is wrong:

```java
new SeatsReserved(eventId, sagaId, ...)   // which "event"? the message, or the show?
```

Two unrelated identifiers of the same type, sharing a name, passed positionally. The contracts fix
it by splitting them — `messageId` for the message, `showId` for the concert (**FR-003**, **SC-007**)
— and by banning the word "event" as a field name anywhere in the module.

T012 already applied the fix. This task is about making it **stick**.

---

## Why a naming rule needs a test

A convention documented in `data-model.md` and applied once holds until the next person edits the
module. They add an eighth message type, name a field `eventTime` because it reads naturally, and
nothing objects. The design document is not in the build; review catches it only if the reviewer
happens to remember.

So the rule is written as an assertion instead:

```java
.filter(name -> name.toLowerCase().contains("event"))
...
assertThat(offenders).isEmpty();
```

This is a different species of test from the other three. T015–T017 assert what the code *does*.
This one asserts what the code *is* — a property of the source, checked at build time. Other things
in this family: no cycles between packages, no `java.util.Date` anywhere, every public API
documented. Whenever you find yourself writing "we always..." in a document, ask whether the build
could say it instead.

**Note it is broader than the task asked for.** T018 says "no component named `eventId`". The test
rejects *any* component containing "event", because `eventName` or `eventTime` would reintroduce
exactly the ambiguity that a narrower check waves through. `data-model.md` states the broad rule, so
the test enforces the rule rather than the example.

---

## Reflection, and how the records are found

**Reflection** is a program inspecting itself at runtime — asking a class what fields it has rather
than knowing at compile time. Java gives records first-class support:

```java
for (RecordComponent component : OrderCreated.class.getRecordComponents()) {
    component.getName();   // "messageId", "sagaId", ...
}
```

The harder question is *which classes to inspect*. The easy answer is a hand-written list:

```java
List.of(OrderCreated.class, SeatsReserved.class, /* ...five more... */)
```

That fails at exactly the moment it matters. The person adding an eighth message type with an
`eventId` field is the same person who would need to add it to this list — and if they remembered
the naming rule, they would not have written `eventId` in the first place. A test that only checks
the types someone remembered to register is a test that passes right when it should fail.

So the test scans the build output directory instead:

```java
Path classesDir = Path.of(Topics.class.getProtectionDomain().getCodeSource().getLocation().toURI());
Files.list(classesDir.resolve("com/marketplace/events"))
     .filter(f -> f.endsWith(".class"))
     .map(this::load)
     .filter(Class::isRecord)
```

Reading it left to right: `Topics.class` is a class we know lives in main sources, so asking where
it was loaded from yields `target/classes` — not the test classes sitting beside this file. List the
package directory, keep the `.class` files, load each one, keep the records. Anything compiled into
the package is examined, registered or not.

### The guard that keeps this honest

```java
@Test
void there_are_exactly_seven_message_records() {
    assertThat(recordsInPackage()).hasSize(7);
}
```

This looks like a redundant count. It is the most important test in the file.

Every other assertion here has the form "for each record found, ...". If the scan ever returned an
**empty list** — a build layout change, a move to running from a jar, a typo in the package path —
every one of those tests would pass. Vacuously. Loudly green, checking nothing.

That is the characteristic failure of reflective and data-driven tests, and it is nasty precisely
because the symptom is *success*. Any test that iterates over a discovered set needs a companion
assertion that the set is not empty. Here it also doubles as an assertion of **FR-004**: exactly
seven message types, no more.

---

## A small Java detail worth keeping

The first version of the scan did not compile:

```
incompatible types: List<Class<capture#1 of ?>> cannot be converted to List<Class<?>>
```

Each call to `load(...)` returns `Class<?>`, and the compiler gives every one its own *capture* — a
distinct anonymous type standing in for the unknown parameter. It cannot then prove a list of those
is a `List<Class<?>>`. The fix is an explicit type witness telling the stream what element type to
use:

```java
.<Class<?>>map(fileName -> load(...))
```

Not something to memorise, but "capture#1 of ?" is a phrase worth recognising: it means the compiler
lost track of a wildcard's identity, and the answer is almost always to name the type explicitly.

---

## Where this sits among the four tests

| Test | Asks |
|---|---|
| T015 round-trip | Does a message survive JSON intact? |
| T016 unknown field | Does an old consumer tolerate a new producer? |
| T017 validation | Can an invalid message be constructed? |
| **T018 naming** | **Does the source obey its own conventions?** |

Written before a single record exists. All four fail right now, and that is the design: T019–T025
turn them green one record at a time, and the failing test list is a to-do list nobody has to
maintain by hand.

---

## Try it yourself

```bash
./mvnw -pl common-events clean test-compile
```

**Expect**: `BUILD FAILURE` — 86 errors, every one of them `cannot find symbol` naming one of the
seven records. Nothing else, which is worth confirming: it means the tests themselves are sound and
only the implementation is missing.

To see what the reflective scan will do once records exist, try the mechanism on a type that is
already there:

```bash
jshell --class-path common-events/target/classes
```

```java
import java.util.Arrays;
record Ticket(String seatId, java.util.UUID eventId) {}
Arrays.stream(Ticket.class.getRecordComponents()).map(c -> c.getName()).toList()
```

**Expect**: `[seatId, eventId]` — and `eventId` is exactly the name this test would flag. That is the
whole mechanism: ask a record what it is made of, and check the answer against a rule.

Type `/exit` to leave.

---

## What comes next

**T019–T025**: the seven records, one per task. Each one turns a slice of these tests green, and the
compiler error list shrinks with every commit. By T025 the module builds again and all four test
files run for the first time.

**T026** then seals `SagaEvent` — the piece T012 had to leave open, now that the seven types it
permits finally exist.
