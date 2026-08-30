# T171 — `SagaEndToEndIT`

**What this did:** wrote the first test in the entire project where BOTH halves of a producer/consumer
contract genuinely exist and get exercised together — a real `OrderCreated`, published exactly the way
order-service actually publishes one, read back on the other end as a real `seats.reserved` by a reader
that owes this service nothing.

---

## Why this is a genuinely new kind of test for this project, not just another integration test

order-service has been publishing `OrderCreated` since a much earlier step. Until this test, nothing in
the whole project ever consumed it — every test proving inventory-service's own booking logic
(`ReservationContentionIT`, `ReservationRejectionIT`, and every other test in this build step) calls
`ReservationService.decide(...)` directly, skipping Kafka entirely. That's the right choice for those
tests: proving thousand-way contention or refusal causes needs precise control over concurrency that
routing everything through a real broker would only get in the way of. But none of those tests can
answer a different question: does a message that actually arrives on the real channel, in the real wire
shape a real producer sends, genuinely trigger this service's logic at all? This test is the one place
in the project that question gets asked, with both a real producer path and a real consumer path
present at once.

## Why an independently-built `ObjectMapper` reads the result, rather than this service's own

If this test read the outcome using the exact same JSON library configuration this service uses to
WRITE it, a bug in that shared configuration would happily cancel itself out — the reader would
misunderstand the message in exactly the same way the writer misworded it, and the test would still
pass. `InventoryKafkaIT`'s `WIRE_MAPPER` (the object doing the reading here) is built completely
independently, with its own separate decisions about dates and unknown fields, matching the same
reasoning `OutboxRelayPortIT` and order-service's own `KafkaPostgresIT` already established: what needs
proving is that the CONTRACT is readable by anyone, not that this service's code agrees with itself.

## Why publishing through Kafka rather than calling a listener method directly

Calling a not-yet-written listener method directly would prove the decision logic again — something
every other test in this build step already does — and would prove nothing about whether the
`@KafkaListener` wiring, the deserializer, or the message shape a real producer sends actually connects
to that logic at all. The whole value of this specific test is in the two real hops it exercises that
no other test does: a message genuinely leaving order-service's shape and genuinely arriving as this
service's own trigger.

## Verifying it — and why this is the plainest possible failure in the whole checkpoint

```text
Tests run: 1, Failures: 0, Errors: 1
java.lang.IllegalStateException: no SeatsReserved for sagaId=... arrived on seats.reserved within PT15S
```

The simplest and most honest failure this whole batch of tests can produce: nothing currently consumes
`order.created` at all, so nothing ever answers. Once `OrderCreatedListener` (T178) exists, this exact
test — unchanged — becomes the project's first genuine end-to-end proof that a real message crossing a
real service boundary produces the real effect it's supposed to.
