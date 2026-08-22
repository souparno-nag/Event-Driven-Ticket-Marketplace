# T016 — Tolerating an unknown field

**What this task did:** added one test to `ContractRoundTripTest` — deserializing a message that
carries a field this build has never heard of, and asserting it is ignored rather than fatal.

Still red: like T015, it exercises records that arrive in T019–T025.

---

## The problem this test guards

You add a `promotionCode` field to `OrderCreated`. You deploy order-service. What happens to
inventory-service, which is still running yesterday's build?

By default in Jackson, it **crashes on every message**. `FAIL_ON_UNKNOWN_PROPERTIES` is enabled out
of the box, so a JSON field with no matching record component throws
`UnrecognizedPropertyException`. Your rolling deployment becomes an outage, and the only way out is
to stop everything, deploy every service at once, and start again — which is the deployment model
distributed systems exist to escape.

T013 disabled that setting. This test is what stops someone re-enabling it, or a future Jackson
version changing the default back, without anybody noticing until the next deploy.

## Why the mismatch is normal, not exceptional

It is tempting to think producer and consumer always agree, since they are built from one
repository. During a deployment they emphatically do not:

```
t=0    order-service v1  ──►  inventory-service v1        agreed
t=1    order-service v2  ──►  inventory-service v1        ← mismatched, for several minutes
t=2    order-service v2  ──►  inventory-service v2        agreed again
```

Every deploy passes through the middle row. And with Kafka, messages **persist**: a message written
by v2 sits in the channel and is still there when v1 reads it, so the window is not even bounded by
how fast you deploy. Being forgiving about unknown fields is what makes that middle row survivable.

The industry name for the principle is **Postel's Law**: be conservative in what you send, liberal
in what you accept. Producers emit exactly the contract; consumers ignore what they do not
recognise.

---

## What it does and does not buy you

This tolerance covers exactly one kind of change:

| Change | Safe for old consumers? | Why |
|---|---|---|
| **Add** a field | ✅ | Old consumers ignore it — this test |
| **Remove** a field | ❌ | Old consumers still require it |
| **Rename** a field | ❌ | Removal and addition at once |
| **Change a type** | ❌ | `"49.99"` will not parse as a number |
| **Add an enum value** | ❌ | `valueOf` throws on a name it lacks |

Only the first row is free. That is why the contracts also carry `schemaVersion` (T012) and pair
every channel with a dead-letter channel (T011): additive change is handled silently by this
setting, and everything else needs a version bump and a way to set aside messages a consumer cannot
safely interpret.

Notice the last row is the same fact that made T010's `RESERVATION_EXPIRED` worth declaring before
anything used it. Adding an enum value is *not* an additive change on the wire.

---

## How the test is written

```java
ObjectNode withExtraField = (ObjectNode) mapper.readTree(mapper.writeValueAsString(original));
withExtraField.put("promotionCode", "SUMMER2026");

OrderCreated restored = mapper.readValue(mapper.writeValueAsString(withExtraField), OrderCreated.class);

assertThat(restored).isEqualTo(original);
```

Serialize a real message, parse it into a **tree** (`ObjectNode`, Jackson's mutable JSON object),
add a field, write it back out, and read it as an `OrderCreated`.

**Why the tree and not string surgery.** The obvious shortcut is
`json.replace("{", "{\"promotionCode\":\"X\",")`. It works until it does not, and when it produces
malformed JSON the test fails with a parse error that *looks* exactly like the tolerance being
missing. A test whose failure can mean two different things costs more than it saves. Going through
the tree guarantees the input is valid JSON, so a failure has exactly one interpretation.

**Why assert equality rather than "it did not throw".** The weaker assertion —
`assertThatCode(...).doesNotThrowAnyException()` — would pass if Jackson silently mangled a known
field while skipping the unknown one. `isEqualTo(original)` says the unknown field was dropped
*and* everything else came through untouched. Both halves matter: ignoring means ignoring, not
absorbing.

---

## Try it yourself

Still red, and still for the right reason:

```bash
./mvnw -pl common-events clean test-compile
```

**Expect**: `cannot find symbol: class OrderCreated`.

You can see the behaviour this test depends on without waiting for the records, using any type at
all:

```bash
./mvnw -q -pl common-events dependency:build-classpath -Dmdep.outputFile=/tmp/cp.txt
jshell --class-path "common-events/target/test-classes:$(cat /tmp/cp.txt)"
```

```java
import com.fasterxml.jackson.databind.ObjectMapper;
import com.marketplace.events.EventJson;
record Point(int x, int y) {}
var strict = new ObjectMapper();
strict.readValue("{\"x\":1,\"y\":2,\"z\":3}", Point.class)
```

**Expect**: `UnrecognizedPropertyException: Unrecognized field "z"`. That is Jackson's default, and
what every consumer would do to a message from a newer producer.

```java
var lenient = EventJson.mapper();
lenient.readValue("{\"x\":1,\"y\":2,\"z\":3}", Point.class)
```

**Expect**: `Point[x=1, y=2]`. The unknown field is gone and the known ones are intact — which is
precisely what this test asserts, on a real message.

Type `/exit` to leave.

---

## What comes next

**T017** — `ValidationTest`, turning the throwaway harness from T014 into real assertions: each of
the eight ways to construct an invalid message, each one rejected.
