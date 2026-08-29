# T162 — `OutboxRelayPortIT`

**What this task did:** wrote the one test that proves the outbox mechanism ported in T130–T133
actually works, end to end, in this module's own Spring context — a pending row reaches its real
channel, keyed by its saga id, and is marked `PUBLISHED` only once the broker genuinely has it.

---

## Why this doesn't re-prove all twelve guarantees

`contracts/outbox-relay.md`'s twelve guarantees already have an exhaustive suite proving them, against
structurally identical code, in order-service. Rebuilding that same suite here would be duplicated
effort, not added confidence — the mechanism itself didn't change when it was ported (research.md R8).
What genuinely hadn't been proven yet was narrower and specific to this module: does the ported class,
wired into *this* service's own context, against *this* service's own schema, over *this* service's own
broker connection, actually do what it already does in order-service? That's the one thing worth a test
of its own, and it's the only thing this file checks.

## Waiting for the real schedule, not calling the method directly — the stronger choice, found necessary rather than merely preferred

```java
await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
    OutboxRecord reloaded = outboxRepository.findById(record.getId()).orElseThrow();
    assertThat(reloaded.getStatus()).isEqualTo(OutboxStatus.PUBLISHED);
});
```

No direct call to `pollAndPublish()` anywhere in this test. That's deliberate, and it's not merely a
style preference for realism: `LapsedReservationSweeper` (T161) is what first discovered that this
service's scheduling infrastructure had never actually been switched on — a missing
`@EnableScheduling` that left every `@Scheduled` method silently inert, undetected specifically because
every earlier test touching `OutboxRelay` called its method directly, bypassing Spring's scheduler
entirely. Waiting for the real timer here is what would have caught that exact gap from the outbox
side too, had T161 not already found and fixed it first. Writing this test to call the method directly
anyway, now that the fix already exists, would have quietly given up the one thing this test is best
positioned to guard against a second occurrence of.

## A real bug this test's own first run caught — in the test, not the system

The first version asserted the received Kafka message's value was byte-for-byte identical to the
payload string as written. It failed — not because the relay sent the wrong thing, but because
PostgreSQL's `jsonb` column type normalises its stored representation (a space inserted after every
colon, among other things), a fact `V4__create_outbox.sql`'s own comments already document explicitly:
the bytes read back are never guaranteed identical to the bytes written. Asserting exact string equality
against a value that passed through `jsonb` was the mistake — fixed by asserting the received value
*contains* the expected content rather than matches it exactly, which is what an assertion checking "did
the right message arrive" should have done from the start rather than accidentally testing PostgreSQL's
own JSON formatting instead.

## Verifying it

Ran for real: a pending row was saved with no relay interaction of any kind, the test's own assertion
waited on nothing but the real scheduled timer, and within the poll window the row reached `PUBLISHED`
with `publishedAt` set, and an independent `KafkaConsumer` — reading the real `seats.reserved` topic
with nothing this service wrote — found the message keyed by the correct order id.
