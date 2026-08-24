# T088 — `KafkaPostgresIT`, a real broker for the tests that need one

**What this task did:** wrote the shared base class every Phase 4 test extends — the first thing in
this build step to start a real Kafka broker inside a test, rather than only a database.

---

## Why `PostgresIT` alone was not enough

Phase 3's tests never needed a broker: accepting an order and writing its outbox row is a database
question, full stop, and `PostgresIT` was built to reflect that — no Kafka container, so those tests
pay no cost for a component they never touch.

Phase 4 is entirely about the relay actually sending messages, so every one of its tests genuinely
needs both a database and a broker. `KafkaPostgresIT extends PostgresIT` rather than duplicating it,
which means every Phase 4 test class reuses the exact same shared PostgreSQL container Phase 3's
tests already start — one database for the whole build, not two — and adds exactly one thing on top:
Kafka.

## Reading back with a consumer this service did not write

```java
protected static List<ConsumerRecord<String, String>> consume(String topic, int minCount, Duration timeout)
```

Every Phase 4 test verifies what the relay actually did by reading messages back with a **plain
Kafka client** — no Spring, no `KafkaTemplate`, nothing this service configured. That is deliberate,
and it is what SC-008 is actually asking for: proving a message is correct on the wire, in a form any
outside reader can consume, rather than proving one object this service built equals another object
this service also built. A bug in how this service's own consumer-side configuration reads messages
back would happily "confirm" a broken producer, if the test used that same configuration to check its
own work.

## Disabling auto-created topics — for two reasons at once

```java
.withEnv("KAFKA_AUTO_CREATE_TOPICS_ENABLE", "false")
```

The first reason is the one build step 1 already established: an auto-created topic gets exactly one
partition, and every ordering-sensitive test in this phase would then pass for the wrong reason — a
single partition orders everything globally, trivially.

The second reason is new to this batch, and turns out to be useful rather than merely safe: with
auto-creation off, a message aimed at a channel nobody explicitly provisioned **genuinely fails to
send** — a real `UnknownTopicOrPartitionException` from a real broker, not a mock standing in for
one. `OutboxRelayIT` and `OutboxOrderingIT` both use exactly this to produce a real send failure on
demand, by pointing a row at a channel name (`no.such.channel`) that was never created.

## One broker, several test classes, provisioned once each

```java
protected static void createTopicIfAbsent(String topic) {
    ...
    } catch (ExecutionException e) {
        if (!(e.getCause() instanceof TopicExistsException)) {
            throw new IllegalStateException(...);
        }
    }
```

The Kafka container, like the PostgreSQL container in `PostgresIT`, is a `static` field started once
and shared across every subclass — the same reasoning: starting a broker costs real time, and five
test classes in this phase would otherwise pay that cost five times per build. Because several test
classes share it, more than one of them will ask for `order.created` to exist; catching
`TopicExistsException` is what makes asking twice harmless rather than a startup failure.

---

## Confirmed

No test in this file — there is nothing to test in a base class — but its correctness was verified
indirectly: `OutboxRestartRecoveryIT` (T093), which extends it and needs everything this class
provides, ran successfully against a real broker and database, confirmed in that task's own notes.
