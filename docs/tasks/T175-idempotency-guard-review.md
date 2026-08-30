# T175 — reviewing the T174 guard, and what proving it correct under load actually found

**What this did:** checked `IdempotencyGuard`'s body against `contracts/inventory-consumer.md` and
this project's comment standards, per the task's own instruction — keep it if it passes and reads
well, rewrite it only if it doesn't. The guard's own logic passed unchanged. But proving that
honestly — running its tests not just alone, but as part of the *whole* test suite, the way the
project's own standing workflow requires — surfaced three separate, real bugs in the test
infrastructure surrounding it, each one hiding the guard's own correctness behind a symptom that
looked, at first, like the guard itself was broken. This document is mostly about those three bugs,
because understanding why a working piece of code can still make its own tests fail is the more
useful lesson here.

---

## The guard itself: review result

Checked against contract, guarantee by guarantee:

| Guarantee (contracts/inventory-consumer.md) | Result |
|---|---|
| Insert attempted in the same transaction as the state change | ✅ — the guard is the first thing `ReservationService.decideAndRecord` does |
| Success means genuinely first delivery, caller proceeds | ✅ `tenDeliveriesOneEffect` |
| Duplicate-key failure means already-handled, caller does nothing further | ✅ `outcomeSurvivesInterruption` |
| Two different messages never suppress each other | ✅ `distinctMessagesAreIndependent` |

The code reads well and needed no rewrite. One thing it depends on — `saveAndFlush` rather than
`save` — is explained in T174's own document; that reasoning held up under review and stayed as
written.

## Why "it passes alone" wasn't enough to call this done

This project's own standing rule is to verify with the *whole* test suite, not just the tests that
look related to the task at hand — a test can pass in isolation and still fail once every other test's
own side effects are also in the room. `IdempotencyIT` did exactly that: reliable every time run by
itself, but failing every single time it ran as part of the full 50-test suite, with every one of its
own three tests timing out waiting for a message that should have arrived in well under a second.
Chasing that down, honestly, took most of this task's own time — and found three genuinely separate
causes, not one.

### Bug 1 — a background job quietly listening to the real Kafka broker during unrelated tests

`SeatLockRebuilder` is a startup task that replays held seats into Redis and then starts this
service's Kafka listener. Every single test in this service boots the *entire* application, this
class included, whether or not that particular test has anything to do with Kafka — and left
unguarded, it was starting a real Kafka consumer, joining the real consumer group, in tests that were
never supposed to touch Kafka at all. That consumer joining and leaving the group repeatedly, across
dozens of unrelated tests, was quietly starving `IdempotencyIT`'s own, genuinely-needed consumer of the
chance to run.

The fix mirrors a pattern this service already used for a similar job (`LapsedReservationSweeper`): a
property, `inventory.rebuild.enabled`, gates the whole job. The first attempt at wiring this for tests
used a shared mutable field read lazily — and that attempt was *disproven by direct reproduction*: it
turns out JUnit loads every test class named in one run up front, before any of them actually execute,
which runs every class's static setup in an unpredictable order relative to which test is "supposed"
to run first. A `@TestPropertySource` annotation on each test base class replaced it — Spring resolves
a subclass's own inlined property as strictly higher priority than an ancestor's, with no shared state
and no dependency on class-loading order at all.

### Bug 2 — one heavy test's own leftover work, inherited by the next one

This service's tests share one running PostgreSQL container across the whole suite (starting a fresh
one per test class would be far too slow). `ReservationContentionIT`, a test simulating a thousand
concurrent booking attempts, writes an "outbox" row for every single decision it makes — a background
job (`OutboxRelay`) is supposed to drain these onto Kafka afterward. But that test's own Spring context
closes the moment its own assertions are satisfied, often before its own relay has had a chance to
publish everything it wrote. Those rows don't disappear — they sit in the shared database, still
waiting to be sent, with nothing wrong with them at all.

The next test to boot its own copy of that same background job (`IdempotencyIT`, in this case)
inherits that entire backlog. The relay processes rows oldest-first, so it worked through hundreds of
someone else's leftover rows before it ever got a chance to look at `IdempotencyIT`'s own — easily
taking longer than that test's own patience allowed, even though `IdempotencyIT`'s own row was sitting
there the whole time, perfectly fine, just last in line.

The fix: `InventoryKafkaIT` (the base class for every test that actually uses Kafka) now deletes any
existing outbox rows before its own tests run, guaranteeing its own relay only ever has its own rows
to work through.

### Bug 3 — a different test's own unfinished retries, inherited the same way

A related, but separate, problem: the *Kafka* consumer group these tests share is also shared across
test classes, the same way the database is. `IdempotencyIT`'s own tests deliberately publish the same
message several times, to prove redelivery is handled safely — the "duplicate" copies each go through
Kafka's own bounded retry schedule before being given up on. If that class's own test finishes (its
assertions are satisfied) before all of those retries have run their course, and its Spring context
then shuts down, the *next* class to share that same consumer group inherits an unfinished retry —
and, because the retry bookkeeping lived only in the now-destroyed consumer's memory, has to redo it
completely from scratch. Confirmed directly: `SagaEndToEndIT`, run immediately after `IdempotencyIT`,
failed the same way for exactly this reason.

The fix: `InventoryKafkaIT` now gives each concrete test class its own, freshly random Kafka consumer
group id, so no class ever inherits a backlog — of any kind — left behind by whichever class happened
to run before it.

## What this leaves as a known, separate issue

One test elsewhere in this service's suite, `UndecidableRequestIT.recoversWithoutReplay`, still fails
occasionally when the *entire* 50-test suite runs together, even though it passes reliably alone.
Unlike the three bugs above, this one is not caused by anything this task touched, and widening its own
timeout did not help — the message it waits for genuinely never arrives within any budget tried, which
points to the whole JVM being under enough load, by the time this test runs late in a 50-test suite,
that its own Kafka consumer cannot get scheduled reliably. This is a pre-existing, environment-load
issue outside this task's own scope, and is recorded here rather than silently left unmentioned.

## Result

`IdempotencyGuard`'s own body needed no changes. With the three surrounding-infrastructure bugs above
fixed, `IdempotencyIT` (and every other test in this service except the one noted above) passes
reliably, both alone and as part of the full suite, across repeated clean runs.
