# T008 — The `RejectionReason` enum

**What this task did:** created the first real source file in `common-events` —
`RejectionReason.java`, a three-value enum naming why a seat-hold attempt failed.

---

## Where this sits in the build

The plan splits the contract module into two waves:

1. **Phase 2 — the vocabulary.** Small shared types that the messages are *made of*: three enums,
   the channel names, a sealed interface, some validation helpers.
2. **Phase 3 — the messages themselves.** The seven records that travel over Kafka.

You cannot write `SeatsRejected` before `RejectionReason` exists, because one of its fields *is* a
`RejectionReason`. So Phase 2 comes first, and this task is the first brick in it.

Nothing consumes this enum yet. That is expected — a foundation is useful before anything stands
on it.

---

## What an enum is, and why one is used here

An **enum** (short for *enumeration*) is a type whose values are a fixed, named list, written out
in the source code. `RejectionReason` can only ever be one of exactly three things:

```java
RejectionReason r = RejectionReason.SEATS_ALREADY_HELD;   // fine
RejectionReason r = RejectionReason.SEATS_ON_FIRE;        // does not compile
```

Compare that with the obvious alternative — a plain `String`:

```java
String reason = "seats already held";
String reason = "Seats already held";
String reason = "seat(s) unavailable";
```

All three are legal Java, all three mean the same thing, and none of them equals the others. A
service on the receiving end has to guess. Someone eventually writes:

```java
if (reason.contains("held")) { ... }   // fragile: breaks the day the wording changes
```

The requirement this task satisfies, **FR-009**, says exactly this: rejection and failure messages
must carry a *machine-comparable* reason "so compensating logic can branch on cause rather than
parsing prose". With an enum, the receiving service writes:

```java
switch (event.reason()) {
    case SEATS_ALREADY_HELD -> ...
    case SEATS_NOT_FOUND    -> ...
    case SHOW_NOT_FOUND     -> ...
}
```

...and the compiler will complain if a new value is added and this `switch` forgets to handle it.
The set being fixed is what makes it safe to branch on. That is the whole point.

---

## Enums and JSON

These messages get serialized to JSON before crossing Kafka. Jackson writes an enum as the plain
name of the constant:

```json
{ "reason": "SEATS_ALREADY_HELD" }
```

...and reads it back the same way. So the enum is not just a Java convenience — the constant names
are literally part of the wire format. That is why they appear verbatim in the JSON Schema for this
message, at `specs/001-event-contracts-foundation/contracts/seats-rejected.schema.json`:

```json
"reason": {
  "enum": ["SEATS_ALREADY_HELD", "SEATS_NOT_FOUND", "SHOW_NOT_FOUND"]
}
```

Two consequences worth internalising:

- **Renaming a constant is a breaking change.** `SEATS_ALREADY_HELD` → `SEATS_HELD` would produce
  messages that older services cannot deserialize. The name is a published contract, not an
  internal detail.
- **Adding a constant is *nearly* safe.** Old consumers still understand every value they knew
  about, but will fail on the new one. That is why the plan pushes hard on getting the value set
  right up front — see the note on `RESERVATION_EXPIRED` in the next task, T010.

---

## Why these three values, and not one, or six

The interesting design question in this file is not *what an enum is* — it is **where to draw the
lines**. Three causes, deliberately kept distinct:

### `SEATS_ALREADY_HELD` — someone else got there first

This is the *contended* case. It is the one that happens a thousand times a second when tickets go
on sale.

Note what the message does **not** say: *which* seat was taken. Seat holds in this system are
all-or-nothing — ask for five seats where one is unavailable and you get zero, not four. If the
rejection named the losing seat, a client could retry seat-by-seat and pick the row clean one seat
at a time, which turns a clean atomic operation into a race. Withholding that detail is a design
decision, not an oversight.

This is also the only value where retrying makes sense. Holds expire after a couple of minutes, so
the same request might well succeed later. The contract does not *promise* that, and nothing
retries automatically.

### `SEATS_NOT_FOUND` — that seat does not exist

The show is real, but a requested seat label is not in its seating chart — asking for "Z99" in a
hall whose rows stop at M.

The reason this is not folded into `SEATS_ALREADY_HELD`: **retrying will never help.** One is bad
luck, the other is a bad request. If a client cannot tell them apart, it either retries forever
against a seat that will never exist, or gives up on a seat it could have had in thirty seconds.
The two need different handling, so they get different names.

### `SHOW_NOT_FOUND` — nothing was even checked

The failure is one level up: no show matches that identifier at all, so the seating chart was never
consulted. That points at a stale client or a mistyped ID, not at seat availability.

The general shape here — *impossible* vs *unlucky*, and *which layer failed* — is a good instinct
to carry into other error-modelling work. Values earn their place by leading to different
behaviour. Two values that always get handled identically should have been one.

---

## The `TRADEOFF:` comment

The project's conventions ask that whenever a design decision has a genuine alternative, the code
records what was rejected and why. Future-you reading this file cold in six months should not have
to re-derive the argument. So the file ends with:

```java
// TRADEOFF: an enum rather than a free-text String field (FR-009). Prose would let the
// inventory service phrase the same cause three different ways ...
// The cost is that adding a cause later means a contract change consumed by every service —
// accepted, because a fixed set is exactly what makes the value safe to branch on.
```

Note that it names the **cost**, not only the benefit. A tradeoff comment that lists only upsides
is marketing. The real cost here is rigidity: a new rejection cause means recompiling and
redeploying every service that reads the enum. That was judged worth paying.

---

## Javadoc, and why it is heavy for 3 lines of actual code

The file is roughly forty lines, of which three carry values. The rest is documentation. Two
project rules drive that:

- The constitution requires public interfaces to be documented where they are defined. This enum
  is consumed by services that have not been written yet, by developers who were not present for
  this decision.
- The project asks for **WHY comments, not WHAT comments**. `// the show was not found` next to
  `SHOW_NOT_FOUND` adds nothing — the name already says it. What is *not* obvious is why it is a
  separate value from `SEATS_NOT_FOUND`, so that is what the comment explains.

A useful test: if deleting a comment loses no information, it was a WHAT comment.

---

## Try it yourself

The module has to compile. From the repository root:

```bash
./mvnw -pl common-events clean compile
```

`-pl` means "project list" — build only that one module instead of everything.

**Expect**: `BUILD SUCCESS`, and a compiled class appearing at
`common-events/target/classes/com/marketplace/events/RejectionReason.class`.

To see the enum's actual runtime shape, `javap` disassembles a compiled class:

```bash
javap -p common-events/target/classes/com/marketplace/events/RejectionReason.class
```

**Expect**: something like

```
public final class com.marketplace.events.RejectionReason extends java.lang.Enum<...> {
  public static final com.marketplace.events.RejectionReason SEATS_ALREADY_HELD;
  public static final com.marketplace.events.RejectionReason SEATS_NOT_FOUND;
  public static final com.marketplace.events.RejectionReason SHOW_NOT_FOUND;
  public static com.marketplace.events.RejectionReason[] values();
  public static com.marketplace.events.RejectionReason valueOf(java.lang.String);
  ...
}
```

That is what an enum really is underneath: a `final` class with three `static final` instances of
itself, and no public constructor — so those three are the only ones that can ever exist. The
`values()` and `valueOf(String)` methods are generated by the compiler for free; `valueOf` is what
Jackson uses when turning `"SEATS_ALREADY_HELD"` back into the constant.

---

## What comes next

**T009** and **T010** create the other two enums — `PaymentFailureReason` and `CancellationReason`.
They are marked `[P]` for parallel in `tasks.md`, meaning they touch different files and depend on
nothing incomplete, so they could be done in any order or all at once.
