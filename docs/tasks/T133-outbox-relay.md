# T133 — `OutboxRelay`, ported and implemented, not re-stubbed

**What this task did:** copied order-service's `OutboxRelay` into this service, with `pollAndPublish`
already fully working — the real method body, not a `TODO(developer)` stub.

This is the one file in this batch worth pausing on before reading the rest, because it's tempting to
assume every ported file in this build step should arrive stubbed the way the two Lua scripts will. It
shouldn't, and the reason why is itself worth understanding.

---

## Why this one is finished, not an exercise

CLAUDE.md's `TODO(me)` markers and this project's broader pattern — a stub with its contract written
out, its tests already failing, left for a human to implement — exist for a specific purpose: some
pieces of a system are worth understanding by *building* them, not by reading someone else's finished
version. `lock_seats.lua`, arriving in a later task, is exactly that kind of piece — four lines that
are the entire difference between a marketplace that double-books and one that doesn't, small enough to
hold in your head at once and consequential enough that working through *why* each line matters teaches
something reading a finished version wouldn't.

`OutboxRelay.pollAndPublish()` was already that exercise — in build step 2, against `order-service`.
It was written, reviewed (`docs/tasks/T099-outbox-relay-implementation.md` and
`T100-outbox-relay-review.md` record that), and proven against five separate integration tests
covering concurrency, ordering, tracing, and restart recovery. Re-stubbing it here and asking it to be
solved a second time wouldn't teach anything new — the twelve guarantees this method has to satisfy are
identical regardless of whether the messages are order confirmations or seat holds, because they're
guarantees about *outbox rows*, not about what the rows mean. Treating "port this exact same lesson
twice" as if it were a fresh exercise would waste effort without adding understanding, which is exactly
the trap the project's own preference for demonstrated need over ceremony argues against repeating.

So this file ships complete. The developer exercises for *this* build step are the two Lua scripts —
genuinely new problems this codebase hasn't solved before — and that's where the deliberate-stub
treatment actually belongs.

---

## What's identical, and the one comment that had to change to stay true

The method body — claim, loop per row with the try/catch scoped to a single row so one failure never
abandons the rest of the batch, `.get()` on the Kafka send so "marked published" can only ever mean
"the broker has it," dirty-checking rather than an explicit `save()` — carries over exactly. So does
the `@Transactional(timeout = 30)` reasoning: `claimBatch`'s row locks are held only as long as this
method's own transaction is open, and a slow, already-handled send failure shouldn't be allowed to blow
past the 3-second budget this service's booking-decision path uses for something with a completely
different risk profile.

One piece of context needed rewriting rather than copying: order-service's own Javadoc explains a subtle
interaction between this method's `initialDelayString` default and a specific Mockito test
(`OrderAcceptanceIT$RollbackWhenTheOutboxWriteFails`) that used to race a background scheduled call
against a mock's stubbing. That explanation is specific to a test that exists in *that* module, not this
one — carrying it forward verbatim would describe a test this codebase doesn't have. It's been trimmed
to the general principle it illustrates (why `initialDelayString` defaults to `0` rather than being left
unconfigured) without the now-irrelevant specific incident.

---

## Verifying it — the whole ported outbox as one working unit

This is the task where T130 through T133 actually got proven to cooperate, not just compile
individually. A temporary Spring Boot test constructed a real `OutboxRecord`, persisted it against a
live PostgreSQL 16 database with T122's actual `outbox` table, called `outboxRelay.pollAndPublish()`
directly, and confirmed:

- the row was correctly claimed by `OutboxRepository.claimBatch` beforehand;
- after `pollAndPublish()` ran, the row's status was `PUBLISHED` with `publishedAt` set;
- a second `claimBatch` call correctly excluded the now-published row.

**Worth being direct about what this test actually exercised.** The test attempted to stub
`KafkaTemplate` with a Mockito mock so it wouldn't depend on a live broker — but Spring Boot's own
auto-configured `KafkaTemplate<Object, Object>` ended up being the one satisfying `OutboxRelay`'s
constructor instead, for reasons not fully chased down (a `@ConditionalOnMissingBean` interaction that
didn't behave as expected against a generically-typed test bean). Consuming the real `seats.reserved`
topic afterward confirmed a real message actually reached the real, already-provisioned Kafka broker
this environment runs via `make up`. That's a *stronger* result than the intended mock, not a weaker
one — it proves the relay genuinely publishes to a real broker and only marks a row sent after real
acknowledgment — so it was left as-is rather than fought into forcing the mock to take effect.
`KafkaProducerConfig` (T136) is a later task and will be what actually settles which template this
service uses at runtime; this test only proves the relay's own logic works correctly against whichever
one it's given.

The test file was temporary and has been removed. `OutboxRelayPortIT` — the durable test this port
actually earns, proving the mechanism works in this module without re-proving all twelve guarantees a
second time — arrives in a later task (T162), once the module's proper test infrastructure exists.

---

## Phase 2's ported outbox is now complete

`OutboxRecord`, `OutboxRepository`, `OutboxMetrics`, and `OutboxRelay` together form a second,
independently working transactional outbox in this service. Combined with Phase 2's domain layer
(T123–T129), everything this service will store and everything it needs to announce a decision now
exists and has been proven against a real database and, in this task's case, a real broker. What
doesn't exist yet is anything that *decides* what to announce — the seat-locking mechanism, the
consumer, and the service method that ties them together are what the remaining Phase 2 configuration
tasks and Phase 3's user story build next.
