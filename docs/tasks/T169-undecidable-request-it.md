# T169 — `UndecidableRequestIT`

**What this did:** wrote the test for SC-018/SC-019 — proving that when a store this service depends
on goes briefly unreachable, nothing is ever wrongly announced, the request is decided anyway the
moment the store recovers with no human involved, and a message that genuinely can never be decided
eventually gets moved aside rather than blocking everything behind it forever.

---

## Why "the store is unreachable" is not the same problem as "the seats are unavailable"

This service can honestly refuse a booking for exactly three reasons: the show doesn't exist, the seat
doesn't exist, or the seat is genuinely held by someone else. "Redis didn't answer in time" is not one
of those three, and answering with one of them anyway would be a lie with real consequences: it
permanently cancels an order that might well have succeeded, over a problem that had nothing to do with
the seats at all. The correct response to "I couldn't find out" is to say nothing yet and try again,
which is exactly what a thrown exception (rather than a returned refusal) inside a Kafka listener
produces — the message stays uncommitted and Kafka redelivers it automatically.

## Why this is the one test class in the whole service with its own private infrastructure

Every other integration test in this service shares one PostgreSQL and one Redis container across the
entire test run — deliberately, because starting a fresh database and cache for every single test
class would make the whole suite far slower for no real benefit. This test class breaks that pattern on
purpose: its entire job is making a store go down and come back up, and doing that to the SHARED Redis
container would break every other test that happens to run afterward in the same test process. So this
class brings up its own disposable PostgreSQL, Redis, and Kafka — used by nothing else — specifically so
it is free to disrupt its own Redis without any consequence outside itself.

## Why the outage is a *pause*, not a *stop*

The obvious way to simulate "Redis is down" is to stop the container. The problem: a Testcontainers
container that gets stopped and started again is not guaranteed to come back on the same randomly
assigned port, and this test's Spring application already has that original port baked into its
connection pool from when it first started. If the port changed underneath it, the "recovery" half of
this test would never actually recover — not because the application did anything wrong, but because
the test broke its own plumbing. Pausing the container's OS-level process, the same operation
`docker pause` performs, freezes it in place with its network identity completely intact: any
connection to it simply hangs until it's unpaused, which is arguably a MORE faithful reproduction of "a
dependency stopped answering" than tearing the container down and rebuilding it ever would be.

## Why `spring.kafka.listener.auto-startup` is deliberately overridden to `true` here

Production leaves listeners off until `SeatLockRebuilder` (T179) has finished restoring Redis from
PostgreSQL — that ordering guarantee belongs entirely to `SeatLockRebuildIT` (T170). This test is
checking a completely different question: what happens to a message once consumption is ALREADY
running normally and a store then goes down mid-flight. Overriding the gate here keeps those two
concerns in the two test classes that actually own them, rather than letting one test's setup
accidentally depend on the other's guarantee.

## Verifying it

```text
Tests run: 4, Failures: 3, Errors: 0
```

`noFalseRefusalWhileDown` already passes — trivially and honestly: with no consumer built yet, nothing
is EVER announced, which satisfies "no false refusal" for the wrong reason but a documented one. The
other three fail exactly as expected: nothing currently reaches `seats.reserved` after recovery, and
nothing currently reaches the dead-letter channel at all, because `OrderCreatedListener`,
`KafkaConsumerConfig`, and the dead-letter wiring (T176–T178) don't exist yet. The pause/unpause
mechanism itself worked cleanly across all four test methods with no container left in a bad state
afterward, which is what this class's own test infrastructure had to prove before any of its four
guarantees could be meaningfully judged at all.
