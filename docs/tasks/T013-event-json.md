# T013 — `EventJson`, the configured `ObjectMapper`

**What this task did:** created `EventJson.java`, a factory producing a Jackson `ObjectMapper` with
four settings changed from their defaults. It also placed the file under `src/test/java` rather than
`src/main/java`, which is a decision worth explaining.

---

## What Jackson is doing here

The seven message types are Java records. Kafka moves bytes. Something has to translate:

```
OrderCreated record  ──serialize──►  {"messageId":"...","amount":"49.99"}  ──► Kafka
                                                                               │
OrderCreated record  ◄──deserialize──  same bytes  ◄───────────────────────────┘
```

**Jackson** is the library that does this in the Java world, and `ObjectMapper` is its main class.
Out of the box it works by reflection: look at the object, find its fields, write them as JSON keys.
For records it uses the component names, and since Jackson 2.12 it can call a record's canonical
constructor to build one back — no annotations required.

The requirement it has to satisfy is **FR-006**: serialize a message, deserialize it, and get back
an object **equal** to the original. That sounds trivial. Three of the four settings below exist
because it is not.

---

## The four settings, and why each default is wrong

### 1. Register `JavaTimeModule`

```java
.addModule(new JavaTimeModule())
```

`occurredAt` is an `Instant`. Without this module Jackson does not know what an `Instant` is, so it
falls back on generic reflection and writes out the internal fields:

```json
"occurredAt": { "seconds": 1755852930, "nanos": 123456789 }
```

That is not a timestamp anyone wants, and it does not read back into an `Instant`. The module
teaches Jackson the `java.time` types explicitly.

This is why `jackson-datatype-jsr310` is a dependency at all — T006's pom has a WHY comment on it for
exactly this reason. It is the single most common Jackson surprise for people new to it.

### 2. Disable `WRITE_DATES_AS_TIMESTAMPS`

```java
.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
```

Even with the module registered, Jackson's default is to write a time as a **number** — epoch
seconds with a fractional part:

```json
"occurredAt": 1755852930.123456789
```

Disabling the setting switches to ISO-8601 text:

```json
"occurredAt": "2026-08-22T09:15:30.123456789Z"
```

Both round-trip correctly, so this is a genuine choice rather than a fix. Two reasons the string
wins here:

- **Legibility.** A large part of choosing JSON over a binary format is that you can read a message
  straight off a channel during a demo or an incident. `1755852930.123456789` throws that away.
- **Precision, in some paths.** Floating-point representations of epoch time are where sub-millisecond
  detail quietly disappears. The ISO string carries all nine digits as text, so the FR-006
  equality assertion holds exactly.

The alternative — epoch millis — was considered and rejected in research decision R3 for both
reasons.

### 3. Disable `FAIL_ON_UNKNOWN_PROPERTIES`

```java
.disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
```

By default, a JSON field that has no matching record component makes Jackson throw.

Consider what that means during a deployment. You add a field to `OrderCreated` and deploy
order-service. For the next few minutes inventory-service is still running the **old** build, which
has never heard of that field. With the default, every message from the new producer crashes the old
consumer, and your rolling deployment becomes an outage.

Ignoring unknown fields is what makes an **additive** change backward compatible: old consumers read
the fields they know and skip the rest. This is **FR-007**, and it is the rule that lets the contracts
evolve without a coordinated stop-the-world redeploy.

Note the limit of this tolerance. It covers *adding* a field. Renaming or removing one still breaks
consumers, which is what `schemaVersion` and the dead-letter channels from T012 are for.

### 4. Enable `WRITE_BIGDECIMAL_AS_PLAIN`

```java
.enable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN)
```

Money in these contracts is `BigDecimal`. First, why:

> **Never use `double` for money.** Binary floating point cannot represent most decimal fractions
> exactly. `0.1 + 0.2` is `0.30000000000000004` in every language that uses IEEE 754 — try it in
> `jshell`. For currency this produces amounts that are wrong by a cent and totals that do not
> reconcile.

`BigDecimal` stores digits and a scale, so `49.99` is exactly `49.99`.

The setting concerns how it is *written*. Jackson may use scientific notation:

```json
"amount": 1E+2        // rather than  "amount": 100.00
```

Both parse back to an equal number, so equality is not at risk — but the *text* differs, and this
project has a rule that reads the text: the simulated payment service declines any amount whose last
digit is 7. Writing plain keeps the serialized form predictable, and keeps amounts legible to
whoever is watching messages go by.

---

## Where the file lives, and why that is not where the task said

The task nominated `src/main/java/com/marketplace/events/EventJson.java`. It was written to
`src/test/java/com/marketplace/events/EventJson.java` instead. Here is the reasoning, because the
constraint is a good illustration of how dependency scope actually bites.

`ObjectMapper` is a class in **`jackson-databind`**. Look back at T006's pom:

```xml
<dependency>
  <groupId>com.fasterxml.jackson.core</groupId>
  <artifactId>jackson-annotations</artifactId>
</dependency>                                    <!-- compile scope -->

<dependency>
  <groupId>com.fasterxml.jackson.core</groupId>
  <artifactId>jackson-databind</artifactId>
  <scope>test</scope>                            <!-- test scope, deliberately -->
</dependency>
```

Test scope means databind is **not on the main compile classpath**. A class in `src/main/java` that
imports `ObjectMapper` simply does not compile.

There were two ways out:

**Promote databind to compile scope.** One line, and the file goes where the task said. But T006
argued against precisely this, in a `TRADEOFF:` comment: a contract module should describe the
*shape* of a message, not decide how it is written to the wire. Every service already has a
configured `ObjectMapper` supplied by Spring Boot; forcing databind onto all of them removes their
freedom to configure it differently and grows the dependency every future module inherits.

**Move the file to test sources.** The mapper is used by the round-trip tests in T015–T016, which
run in test scope where databind is already present. T013's own wording anticipates this — *"test
scope is acceptable if production code never serializes here"* — and production never does.

The second is the option consistent with the design already argued and committed, so it won.

### The cost, stated plainly

These four settings are now **not** automatically shared with the services built in later steps.
That is a real drift risk, and pretending otherwise would be worse than naming it.

It turns out to be small, because Spring Boot's defaults already agree with three of the four:

| Setting | Spring Boot default | Match? |
|---|---|---|
| `JavaTimeModule` registered | Registers every Jackson module on the classpath | ✅ |
| `WRITE_DATES_AS_TIMESTAMPS` | Disabled | ✅ |
| `FAIL_ON_UNKNOWN_PROPERTIES` | Disabled | ✅ |
| `WRITE_BIGDECIMAL_AS_PLAIN` | **Not** set | ❌ |

So each service needs exactly one line of configuration:

```yaml
spring:
  jackson:
    serialization:
      write-bigdecimal-as-plain: true
```

One line per service, against a hard dependency every consumer carries forever. That is the trade,
and it is recorded in the file so the next reader does not have to reconstruct it.

---

## Two smaller decisions

**A new mapper per call, not a shared static one.** `ObjectMapper` is thread-safe once configured,
so a single shared instance is the usual advice and is correct in production. In a test helper it is
a liability: the object is thread-safe but *not immutable*, so any test could reconfigure it and
silently change what every other test means. Constructing one is cheap next to running a test.

**`JsonMapper.builder()` rather than `new ObjectMapper()`.** The builder is Jackson's newer style and
returns a mapper whose configuration is finished at construction. The older approach —
`new ObjectMapper()` then a series of `configure(...)` calls — leaves a window in which the object
exists half-configured. Not a real hazard here, but the builder reads better and is the direction
Jackson is moving.

---

## A caution worth reading twice

There is a comment at the bottom of the file carried over from research decision R3:

> `Instant` round-trips at **nanosecond** precision through JSON. PostgreSQL `timestamptz` stores
> **microseconds**.

Nothing crosses a database yet, so it does not bite in this step. From build step 2 it will: write an
`Instant` to Postgres, read it back, compare it to the original, and the assertion fails because the
last three digits were truncated on the way in.

What makes this nasty is how the failure *looks*. Not "precision lost" — just two timestamps that
print almost identically failing an equality check. People lose afternoons to it. The fix is to
truncate to microseconds before comparing anything that has been through the database.

Writing the caution down at the point where the precision is chosen, rather than discovering it in
step 2, is the entire purpose of the R3 research note.

---

## Try it yourself

The file is in test sources, so `compile` will not touch it. Use `test-compile`:

```bash
./mvnw -pl common-events clean test-compile
```

**Expect**: `BUILD SUCCESS`, 5 main sources and 1 test source compiled.

Then see the settings working. The classpath needs both the compiled test classes and the Jackson
jars, which `dependency:build-classpath` can produce for you:

```bash
./mvnw -q -pl common-events dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt
jshell --class-path "common-events/target/test-classes:$(cat /tmp/cp.txt)"
```

```java
import com.marketplace.events.EventJson;
import java.math.BigDecimal;
import java.time.Instant;
var m = EventJson.mapper();
m.writeValueAsString(Instant.parse("2026-08-22T09:15:30.123456789Z"))
m.writeValueAsString(new BigDecimal("100.00"))
```

**Expect**: the timestamp as a quoted ISO-8601 string keeping all nine fractional digits, and
`100.00` rather than `1E+2`.

Now compare against Jackson's untouched defaults:

```java
import com.fasterxml.jackson.databind.ObjectMapper;
var plain = new ObjectMapper();
plain.writeValueAsString(Instant.parse("2026-08-22T09:15:30.123456789Z"))
```

**Expect**: an error — `Java 8 date/time type java.time.Instant not supported by default`. Jackson
tells you outright to register the module. That message is the single most-searched Jackson error
there is, and now you know exactly what it means.

While you are in `jshell`, the floating-point demonstration is one line and worth seeing once:

```java
0.1 + 0.2
new BigDecimal("0.1").add(new BigDecimal("0.2"))
```

**Expect**: `0.30000000000000004` and `0.3`. That is why money is a `BigDecimal`.

Type `/exit` to leave.

---

## What comes next

**T014** — `Validation.java`, the shared rules the records enforce in their constructors: non-null
fields, `sagaId` equal to `orderId`, seat lists non-empty and duplicate-free, money non-negative with
exactly two decimal places. It is the last piece of Phase 2, and it is what makes an invalid message
impossible to construct rather than merely unlikely.
