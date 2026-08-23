# T048 — Proving per-order ordering against a real broker

**What this task did:** added `OrderingGuaranteeIT`, which publishes **500 messages across 100
orders from 8 concurrent threads** into a real Kafka broker and asserts that every order's messages
come back in the order they were sent. Five assertions, all passing, in about five seconds.

---

## The guarantee, stated precisely

This is the point on which the whole saga design rests, and it is narrower than it is usually
described.

**Kafka orders messages within a single topic-partition.** Not within a topic. Not across topics.
Within one partition of one topic.

That alone is not useful — the system needs "all messages for order #57 stay in order" and Kafka
offers "all messages in partition 2 stay in order". The bridge is the **partition key**: Kafka
hashes the key to pick a partition, so keying by `sagaId` sends every message for one order to the
same partition. The order-level guarantee is then a consequence of the partition-level one.

FR-026 and FR-027 are the same mechanism read from two directions:

- **FR-026** — key by saga id, so one order's messages share a partition and stay ordered.
- **FR-027** — use more than one partition, so *different* orders land on different partitions and
  proceed concurrently.

One partition would satisfy FR-026 perfectly and destroy the system's throughput. Keys without
multiple partitions would be pointless. Neither works alone.

---

## What the test does

| | |
|---|---|
| Orders | 100 |
| Messages per order | 5 |
| Total messages | 500 |
| Publisher threads | 8 |
| Partitions | 3 |

Two properties of the publishing schedule are arranged rather than hoped for:

**Each order is owned by exactly one thread.** If two threads published messages for the same order,
"the order they were produced" would not be a defined thing, and no assertion about it could mean
anything. This is the kind of detail that makes the difference between a concurrency test and a
concurrency-flavoured coin flip.

**Each thread publishes round-robin** — message 1 of all its orders, then message 2 of all its
orders — so orders interleave by construction while each order's own sequence still increases.

### The producer setting the whole thing depends on

```java
config.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
config.put(ProducerConfig.ACKS_CONFIG, "all");
```

Without idempotence, a send that fails and is retried can be written *after* a later send that
succeeded first — reordering messages inside a partition. The guarantee would then hold only when
nothing goes wrong, which is not what the word guarantee means. It is the default in modern clients;
it is set explicitly because everything else here is downstream of it.

---

## The five assertions

1. **Per-order ordering** — group consumed messages by saga id, compare each group against what was
   published. This is SC-011.
2. **Nothing was lost** — all 500 arrived, none twice. The **anti-vacuity guard**: a group of three
   messages in the right order is still in the right order, so an ordering assertion alone can pass
   while messages vanish.
3. **Each order used exactly one partition** — FR-026's mechanism, asserted directly rather than
   inferred. If an order were ever split, assertion 1 would start failing *intermittently*, which is
   a far worse way to learn the same fact.
4. **All three partitions were used** — FR-027. Without this, a single-partition topic would satisfy
   every other assertion perfectly, because total ordering trivially preserves per-order ordering.
   That is exactly the silent failure T029 disabled auto-creation to prevent.
5. **Orders interleave** rather than arriving one order at a time.

### Why a real broker instead of a mock

The behaviour under test **belongs to Kafka**. Key hashing, partition assignment, and per-partition
ordering are broker properties, not properties of this module's code. A mock would assert that the
test's own fake behaves the way the test expects — which is worth nothing at all. Testcontainers
starts the real broker, pinned to `confluentinc/cp-kafka:7.7.1`, the exact image the environment
already runs, so the test needs no download beyond what `make up` pulled and cannot pass against a
version the project does not use.

---

## Proving the test can fail

Three mutations. **Two were caught. One was not**, and that is worth more than if all three had been.

| Mutation | Result |
|---|---|
| Publish with a random key instead of `sagaId` | ✅ **2 failures** — ordering and one-partition |
| Create the topic with 1 partition | ✅ **1 failure** — partition distribution |
| Publish each order contiguously instead of round-robin | ❌ **still passed** |

The first two are the important ones: removing FR-026's mechanism breaks the test loudly, and so
does removing FR-027's.

**The third told me a comment I had written was wrong.** I had claimed the round-robin schedule was
what made interleaving deterministic "rather than a race the test hopes to win". The mutation shows
otherwise: with eight concurrent threads, orders interleave within a partition whether the schedule
is round-robin or not, so the assertion cannot distinguish the two.

The round-robin is still worth keeping — it is insurance against a slower or busier machine where
the threads might serialise — but it is not what the assertion measures, and the comment now says
so. A mutation that fails to fail is information: it marks the boundary of what an assertion
actually covers.

---

## A gap in FR-026 as worded

FR-026 says keying "so that all messages belonging to one order are delivered and processed in the
order they were produced". Taken literally that is **not true of this system**, and the difference
matters enough to record.

The saga publishes each message type to its **own channel** — `order.created`, `seats.reserved`, and
so on. So one order's messages are spread across seven topics. Keying by saga id sends them to the
same *partition number* of each topic, but partition 2 of `order.created` and partition 2 of
`seats.reserved` are separate logs with independent offsets and no ordering relationship whatsoever.

**The saga is still correct**, for a reason that has nothing to do with Kafka: each step is *caused
by* consuming the previous one. `SeatsReserved` cannot be published until `OrderCreated` has been
handled. Causality sequences the saga, and the broker never has to.

Where it will matter is a consumer reading **several channels at once** — the projection service in
build step 6. It can legitimately observe `OrderConfirmed` before `SeatsReserved` for the same order
if it is behind on one channel, and it must be written to tolerate that rather than assuming an
order it was never promised.

This is recorded in the test's own class comment, so the guarantee is not over-claimed by anyone
reading it later.

---

## A real obstacle: Docker API 1.32 vs Docker Engine 29

The test would not run at all at first:

```
Could not find a valid Docker environment. Please see logs and check configuration
```

Which reads like a missing daemon — and Docker was running fine. The real cause was one level down:

```
client version 1.32 is too old. Minimum supported API version is 1.40
```

The docker-java client Testcontainers uses negotiates API 1.32 by default, and **Docker Engine 29
removed support for anything below 1.40.**

Two things about the fix are worth recording, because both were arrived at by testing rather than
guessing:

- **Upgrading Testcontainers is not the fix.** I bumped 1.19.8 → 1.21.3 first, and it changed
  nothing — the client still negotiated 1.32. I then confirmed Boot's managed 1.19.8 works once the
  API version is set, and **reverted the bump**: an unnecessary divergence from the version Spring
  Boot tested against, based on a diagnosis that turned out to be wrong.
- **The `DOCKER_API_VERSION` environment variable does not work.** Verified — docker-java ignores
  it. The system property `api.version` is what it reads.

So `docker.api.version` is a root-pom property, passed to the test JVM by Surefire:

```xml
<docker.api.version>1.40</docker.api.version>
```

**1.40 rather than something newer** because it is exactly Docker 29's stated minimum and has been
supported since Docker 19.03 — one value that works on both very new and quite old engines. A higher
number would fix this machine and break an older one.

---

## Dependencies, and FR-010

Four new dependencies — `kafka-clients`, `testcontainers:kafka`, `testcontainers:junit-jupiter`,
`slf4j-simple` — all **test scope**. Test-scope dependencies do not propagate to anything that
depends on this module, so `common-events` still publishes with exactly one compile dependency:

```
+- com.fasterxml.jackson.core:jackson-annotations:jar:2.17.3:compile
```

Verified after the change. FR-010 holds, and Scenario 2's dependency-tree check is unaffected.

---

## In one line

500 messages, 100 orders, 8 threads, one real broker — proving that keying by saga id turns Kafka's
per-partition ordering into the per-order ordering the saga needs, with the boundaries of that proof
written down rather than assumed.
