# T139 — `InventoryKafkaIT`, the shared broker-backed test foundation

**What this task did:** wrote `InventoryKafkaIT`, extending `InventoryIT` (T138) with a real Kafka
broker, the four channels this service actually touches, and a set of helpers for publishing and
awaiting messages as an independent reader would see them — not as this service's own code would
report them to itself.

This is the base every test in User Story 3 will build on: consuming `order.created`, guarding
against redelivery, and proving the outbox relay actually reaches the wire. Structurally, it follows
order-service's own `KafkaPostgresIT` closely — the "why extend rather than duplicate" reasoning and
the singleton-container pattern are identical and don't need re-deriving. What's worth explaining is
where this class's *content* genuinely differs, and why.

---

## Exactly four channels, not seven

```java
createTopicIfAbsent(Topics.ORDER_CREATED);
createTopicIfAbsent(Topics.dlt(Topics.ORDER_CREATED));
createTopicIfAbsent(Topics.SEATS_RESERVED);
createTopicIfAbsent(Topics.SEATS_REJECTED);
```

`order-service`'s equivalent class only ever needed to provision `order.created` itself — that
service only publishes. This service both consumes and produces, and the set of channels it needs
reflects that asymmetry precisely: `order.created` to consume, `order.created.DLT` because a message
this service cannot decide has to go somewhere (FR-048), and `seats.reserved`/`seats.rejected` to
publish. What's *not* here matters as much as what is: `seats.reserved.DLT` and `seats.rejected.DLT`
are deliberately absent. This service never consumes its own outcome messages — whichever future
service subscribes to them (payment-service, in step 4) owns provisioning their dead-letter channels
for its own tests, the same way this class only provisions the one DLT this service's own consumer
actually needs.

## An independent `ObjectMapper`, built fresh rather than borrowed

```java
protected static final ObjectMapper WIRE_MAPPER = new ObjectMapper()
        .registerModule(new JavaTimeModule())
        .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
        .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
```

It would have been shorter to `@Autowire` this service's own `JacksonConfig`-produced `ObjectMapper`
bean and reuse it here. That would also have been the wrong thing to do, for a reason worth stating
plainly: this class's entire purpose is proving what an *independent* reader — something that built
its own understanding of the contract, not a piece of this service's own configuration — can make of
a message on the wire (SC-009). An `ObjectMapper` borrowed from the service under test would happily
deserialize whatever that same service just serialized, using the identical settings tuned for the
identical purpose. That proves the mapper agrees with itself, not that the *contract* is genuinely
readable by anyone else. It's the same reasoning order-service's own class gives for a raw
`KafkaConsumer` instead of this service's consumer-side code — just applied one layer further in, to
the JSON itself rather than only to the channel.

## The awaiting helpers, and the specific bug they were built to avoid

```java
protected static SeatsReserved awaitSeatsReserved(UUID sagaId, Duration timeout);
protected static SeatsRejected awaitSeatsRejected(UUID sagaId, Duration timeout);
```

These don't just read "the next message on the topic" — they filter by `sagaId` among everything
collected so far. That distinction matters concretely once more than one test shares a topic on the
same broker (the same singleton-container reasoning that makes this efficient also means tests see
each other's messages): a helper that simply grabbed the first record to arrive would occasionally
succeed by finding a completely unrelated test's message that happened to arrive first, and report a
false pass. Verified directly, not merely reasoned about: a temporary test published two distinct
`SeatsReserved` messages with two different saga ids onto the same topic, and confirmed
`awaitSeatsReserved` correctly returned the one actually asked for, not merely the first one that
appeared.

---

## Verifying it

All in a temporary test, not committed: the four topics existed and accepted messages; an
`OrderCreated` published through `publishOrderCreated` came back correctly deserialized by an
independent reader reading `order.created` directly; and both `awaitSeatsReserved` and
`awaitSeatsRejected` correctly located their own message among a shared topic carrying more than one
test's data, rather than merely the first thing that happened to arrive.
