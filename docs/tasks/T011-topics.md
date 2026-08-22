# T011 — `Topics.java`, the channel names

**What this task did:** created `Topics.java` — the seven channel names as constants, a helper
producing dead-letter channel names, and a list of all seven.

---

## What a "channel" is here

The design documents say *channel*; Kafka calls the same thing a **topic**. A topic is a named
stream of messages. A service **produces** to a topic; other services **consume** from it, each
tracking its own position independently. Nobody addresses anybody directly:

```
order-service ──publishes──► "order.created" ──┬──► inventory-service reads
                                               └──► projection-service reads
```

Order-service does not know inventory-service exists. It states a fact — *this order was created* —
onto a named stream, and whoever cares subscribes. That indirection is what "event-driven" buys:
adding a fourth consumer later requires changing nothing in the producer.

This project uses one topic **per message type** rather than a single shared "events" topic. That
way a consumer that only cares about payments subscribes to payment topics and never deserializes
an order message to discover it did not want it.

---

## Why the names live in the contract module

This is the point of the task, and it is a slightly subtle failure mode.

Suppose each service configured its own topic name in its `application.yml`. Order-service
publishes to `order.created`. Someone sets up inventory-service with `orders.created` — plural,
a typo. What happens?

**Nothing happens.** No error, anywhere.

- The producer writes successfully. Kafka accepts messages to `order.created` and is perfectly
  happy.
- The consumer subscribes successfully to `orders.created`, an empty topic, and waits politely.
- Every service reports healthy. Every dashboard is green.
- Orders simply stop progressing past `PENDING`, and nothing says why.

Compare that with the name held in one compiled constant:

```java
@KafkaListener(topics = Topics.ORDER_CREATED)
```

Now a typo is `cannot find symbol: ORDER_CREATEDD` and the build fails in two seconds.

The general principle: **a mismatch between two systems should fail loudly and early, not silently
and at runtime.** Configuration strings duplicated across services fail silently by default. Moving
the string into shared compiled code converts a class of runtime mystery into a compile error.

---

## Why constants and not an enum

An enum genuinely looks better here. It would pair each message type with its channel, and let the
compiler check you handled every case.

It was rejected for one concrete reason: **an annotation argument in Java must be a compile-time
constant.** This is legal:

```java
@KafkaListener(topics = Topics.ORDER_CREATED)     // a static final String — inlined by the compiler
```

This does not compile:

```java
@KafkaListener(topics = Topic.ORDER_CREATED.channelName())   // a method call on an enum
```

Annotations are baked into the class file at compile time, so their arguments must be known then.
An enum constant's *field* is not a compile-time constant expression; a `static final String`
initialised with a literal is — the compiler substitutes the literal at every use site.

Since annotations are exactly where these values are needed most, the tidier design loses. That
argument is recorded in the file as a `TRADEOFF:` comment, because "why isn't this an enum?" is a
reasonable question a reader will have.

---

## The naming convention

```
order.created      seats.reserved      payment.succeeded
└─┬──┘ └──┬───┘
subject  past-tense verb
```

Two deliberate choices:

- **Subject first.** `order.created` and `order.cancelled` sort next to each other in any topic
  listing. `created.order` would scatter them.
- **Past tense.** Every one of these announces something that *already happened*. That is what
  distinguishes an **event** from a **command**.

The second is worth dwelling on, because it is the difference between the two ways of building this
system:

| | Command | Event |
|---|---|---|
| Name | `order.reserve` | `seats.reserved` |
| Means | "please do this" | "this happened" |
| Sender knows | who should act | nothing about who cares |
| Receiver may | decline | not decline — it is already true |

This project uses **choreography**: services publish facts, and other services decide for themselves
what to do about them. There is no coordinator issuing instructions. Naming a topic `order.reserve`
would invite someone to treat it as an instruction that can be refused, which is a different
architecture wearing the same clothes. The naming keeps the intent visible.

---

## Dead-letter channels

Each of the seven has a partner, named with `.DLT` appended — "dead letter topic".

```java
Topics.dlt(Topics.ORDER_CREATED)   // "order.created.DLT"
```

Seven plus seven is the **fourteen channels** the spec keeps referring to.

### What a dead-letter channel is for

Some messages can never be processed. A bug, a schema version the consumer does not recognise,
data that violates an assumption. Retrying does not help — it will fail identically forever.

The consumer now has three options, and only one is acceptable:

1. **Keep retrying.** The message sits at the head of the queue and nothing behind it is ever
   processed.
2. **Discard it.** The stall goes away, and so does all evidence. In a system moving money and seat
   inventory, a saga is now stranded with no record of why. The spec prohibits this outright
   (**FR-023**).
3. **Move it aside** to the dead-letter channel and continue. The queue drains; the message is
   preserved for inspection and can be replayed after the bug is fixed.

Option 3 is the dead-letter pattern, and the third bullet is the whole justification: *aside* is not
*gone*.

### Why blocking is worse here than usual

A Kafka topic is split into **partitions**, and ordering is guaranteed only within one partition.
This project keys every message by saga id (**FR-026**), so all of one order's messages land in the
same partition and arrive in order — a payment result can never overtake the reservation result
that preceded it.

The cost of that guarantee: a partition is a strict queue. So a stuck message does not block only
its own order — it blocks **every order whose key happens to hash to the same partition**. One
poisoned message takes down a third of your traffic (there are three partitions). That is why
FR-025 insists on moving it aside rather than retrying.

### Why seven DLTs and not one

A single shared `dead-letters` topic would be fewer moving parts. But then it holds a mixture of
message shapes, so any tool inspecting or replaying it must first work out what each record *is*
before it can deserialize it. Keeping the pairing means a failed message stays identifiable as the
saga step that produced it.

The `.DLT` suffix is not invented — it is the default used by Spring Kafka's
`DeadLetterPublishingRecoverer`, so consumers get this behaviour with almost no configuration, and
any Spring-familiar reader recognises the name immediately.

---

## `ALL`, and the drift risk

```java
public static final List<String> ALL = List.of(ORDER_CREATED, SEATS_RESERVED, ...);
```

`List.of(...)` returns an **unmodifiable** list — calling `add()` on it throws
`UnsupportedOperationException`. That is deliberate: nothing should be able to add a channel at
runtime that the provisioning step never actually created.

`ALL` exists so that anything needing to walk the complete set — provisioning, health checks,
tests — reads one authoritative list instead of keeping its own copy.

Which leads to the honest weakness in this design. Task **T044** creates the topics with a *shell
script*, `create-topics.sh`, which cannot read Java constants. So the fourteen names exist in two
places:

```
Topics.java  ──►  used by every service at compile time
                                                          ⚠ these can drift apart
create-topics.sh  ──►  used once at container startup
```

Why a shell script at all? The original plan (research decision R4) was to declare topics as Spring
`NewTopic` beans, which Kafka creates at application startup and which live right next to the
constants. That works from build step 2 onward — but build step 1 has *no Spring application*. This
module is deliberately framework-free, and the environment must come up healthy with all fourteen
channels present before any service exists.

Rather than pretend this is free, the plan names the cost and covers it with a test: **T046**,
`TopicNameDriftTest`, asserts that `ALL` has exactly seven entries and that `dlt()` produces the
same suffix the script uses. This is why `DLT_SUFFIX` is package-private rather than inlined — the
test can read it.

That pattern is worth stealing: when a constraint forces you to duplicate a fact, write the test
that fails when the copies disagree. Duplication you have made *loud* is manageable; duplication
that fails silently is the one that costs you a weekend.

---

## Try it yourself

```bash
./mvnw -pl common-events clean compile
```

**Expect**: `BUILD SUCCESS`, `Compiling 4 source files`.

To see the constants and the helper actually behave:

```bash
jshell --class-path common-events/target/classes
```

```java
import com.marketplace.events.Topics;
Topics.ALL
Topics.ALL.size()
Topics.dlt(Topics.PAYMENT_FAILED)
Topics.ALL.stream().map(Topics::dlt).toList()
Topics.ALL.add("sneaky.topic")
```

**Expect**: the list of seven names; `7`; `"payment.failed.DLT"`; the seven dead-letter names — and
the last line throwing `UnsupportedOperationException`, which is the unmodifiable list doing its
job.

Note that the seven plus their seven partners is the full set of fourteen channels that
`make up` will create in Phase 4. Type `/exit` to leave.

---

## What comes next

**T012** — `SagaEvent.java`, a **sealed interface**. Sealed means the interface names, in its own
source file, the complete list of types allowed to implement it. It is a newer Java feature and a
genuinely useful one, because it lets a `switch` over message types be checked for exhaustiveness by
the compiler.
