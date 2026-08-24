# T095 — The producer, and a bug caught before it could break every test

**What this task did:** wrote the `KafkaTemplate<String, String>` bean the relay publishes with —
and, while writing it the obvious way first, found a mistake that would have silently broken every
Kafka-backed test in this build step.

---

## Sending bytes, never rebuilding an object

```java
config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
config.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
```

Both the key and the value are plain strings — never a JSON serializer of any kind. The message
inside an outbox row was already turned into text once, by `OutboxWriter` (T081), at the exact
moment the row was written. Giving this producer a JSON serializer would mean parsing that text back
into an object and immediately turning it back into text again on the way out — a round trip that
buys nothing and reopens exactly the risk `OutboxWriter`'s design was meant to close: a
re-serialization step is a second place the `1E+2` money bug could sneak back in, since
`WRITE_BIGDECIMAL_AS_PLAIN` (T070) would have to be configured correctly on *this* mapper too, and
there is no mapper here — `StringSerializer` cannot re-serialize anything even by accident.

## The mistake this task's first draft made

The obvious way to write a `ProducerFactory` is a hand-built map naming exactly the settings you
care about:

```java
Map<String, Object> config = Map.of(
        ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
        ProducerConfig.ACKS_CONFIG, "all",
        ...);
```

This compiles, looks complete, and is wrong in a way that would not have shown up until the very
first Kafka-backed test ran: it never mentions `bootstrap.servers` at all. Left unset, the Kafka
client falls back to its own default, `localhost:9092` — which happens to be *correct* for a
developer running the real environment locally, and *silently wrong* for every integration test in
this project, all of which point Kafka at a Testcontainers broker on a randomly assigned port via
`spring.kafka.bootstrap-servers`, injected through `@DynamicPropertySource` in `KafkaPostgresIT`. A
hand-built map has no way to see that override; it was never asked to look.

Caught before it could cause a single failing test, by asking a simpler question first: *where does
this configuration actually come from?* The answer was already sitting in Spring Boot, unused.

## The fix: build on Spring Boot's own resolved configuration

```java
public ProducerFactory<String, String> outboxProducerFactory(KafkaProperties kafkaProperties) {
    Map<String, Object> config = new HashMap<>(kafkaProperties.buildProducerProperties(null));
    config.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
    ...
```

`KafkaProperties` is the same object Spring Boot's own autoconfiguration reads `spring.kafka.*`
into — including `bootstrap-servers`, resolved from wherever it is actually configured, whether
that's `application.yml` in production or a test's dynamic override. `buildProducerProperties(null)`
returns that fully-resolved map, and this bean only *adds* the handful of settings it specifically
cares about on top, rather than starting from nothing and hoping every setting that matters got
remembered.

## The rest of the settings, and why each exists

**`acks=all`** — an acknowledgement is only allowed to mean "every in-sync replica has this message,"
never merely "the leader received it and might still lose it." `OutboxRelay`'s guarantee that a row
is marked `PUBLISHED` only *after* the broker acknowledges (contract guarantee 4) is worth nothing if
"acknowledged" is defined loosely.

**`enable.idempotence=true`** — stops Kafka's own low-level retries from silently reordering or
duplicating a message within a partition. This is a different concern from this project's own
retry-then-park logic: that logic operates at the level of "did this whole send attempt fail," while
idempotence operates *inside* a single attempt, keeping the client's automatic retries honest.

**`max.in.flight.requests.per.connection=5`** — the idempotent producer's own upper limit for staying
ordering-safe. Kafka enforces this ceiling itself; writing it down explicitly turns an implicit
client default into a documented decision, so a reader doesn't have to already know that number to
understand why ordering holds.

---

## Confirmed

Ran `SchemaIT` — a test that loads the *whole* Spring context, including this new bean — to smoke-test
that everything wires together cleanly:

```text
Tests run: 2, Failures: 0, Errors: 0 -- SchemaIT
BUILD SUCCESS
```

No bean conflicts, no missing configuration. The real proof this bean resolves `bootstrap-servers`
correctly comes later, once T099's implementation actually sends something through it and Phase 4's
tests can observe messages arriving at the Testcontainers broker they expect.
