# A guide to writing `IdempotencyGuard.isFirstDelivery`

This is the one method in this whole build step you write by hand. Everything around it — the table,
the entity, the repository, and the class that will call this method — already exists. This document
is not a spec (that's `contracts/inventory-consumer.md`, and you should have it open alongside this).
It's a walk-through: what the problem actually is, why the fix takes the shape it does, and what tends
to go wrong, aimed at someone meeting this pattern for the first time.

---

## 1. What "at-least-once delivery" actually means, and why it's not a bug

Kafka promises every message published to a topic is delivered to a consumer *at least* once. It does
NOT promise *exactly* once. If there's any doubt at all about whether a consumer actually finished
handling a message — the consumer crashed, a network blip happened right as it was about to confirm
receipt, a rebalance moved partitions to a different consumer mid-processing — Kafka's answer is always
"send it again, just in case." That's a deliberate design choice, not a flaw: the alternative (risk
occasionally losing a message rather than ever duplicating one) would be far worse for a system whose
messages represent real money and real seats.

The consequence: this service WILL, sooner or later, receive the exact same `OrderCreated` message more
than once. Not as an edge case to defend against "just in case" — as a normal, expected, and frequent
occurrence in any system that runs for long enough.

## 2. Why the consumer has to solve this, not the producer

order-service, which publishes `OrderCreated`, has already done its job the moment the message is
safely written to the topic. It has no way to know whether THIS service successfully finished acting on
it — that information doesn't exist yet at the moment order-service publishes. Only the consumer is
ever in a position to say "wait, I've already done this one." That's why this guard lives here, in
inventory-service, and not as some kind of "don't resend" logic on the producing side.

## 3. What actually goes wrong if nothing catches a duplicate

Picture the SAME `OrderCreated` message arriving twice, with nothing here to notice. Both deliveries
would run `ReservationService.decide(...)` in full: both would try to hold the SAME seats in Redis for
the SAME order id. Because the Lua script's own contract (`contracts/seat-lock-scripts.md`, guarantee
3) treats "already held by this exact order" as acquirable — a deliberate design that lets a legitimate
retry succeed instead of refusing itself — BOTH deliveries would successfully re-acquire the hold. Then
both would try to write a `reservations` row for the same order. The database's own `order_id UNIQUE`
constraint would eventually catch that specific collision, but only after real work was already
attempted twice, and only for that one specific mistake — a slightly different bug (two deliveries
racing each other more subtly) might not be caught by any constraint at all. The fix has to happen
before any of that work is attempted a second time, not after something breaks.

## 4. Why the insert has to happen in the SAME transaction as the actual state change

Imagine, instead, that this guard recorded "I've seen this message" in its own separate transaction,
committed immediately, BEFORE the rest of the booking decision even started. Now imagine the booking
decision itself then fails for some unrelated reason — a database hiccup, say — after the guard's own
row was already safely committed. The message gets redelivered (correctly — the work never actually
finished). But this guard now sees its own already-committed row and says "already handled, skip" —
even though nothing was ever actually decided. The order would sit forgotten forever, with the one
record that could have explained why insisting nothing was wrong.

The fix: the insert into `processed_messages` and every other change this delivery makes (the
reservation, the seat rows, the outbox row) must all commit together, as one atomic unit, or not at
all. If the transaction fails partway through, EVERYTHING in it — including the guard's own row —
rolls back together, and a redelivery finds a clean slate and processes normally. If the transaction
succeeds, the guard's row and the real work are guaranteed to be true together. This is exactly why
`contracts/inventory-consumer.md` puts the guard's insert as step 4, still inside the same
`BEGIN TRANSACTION` the rest of the decision runs in, rather than as something that runs before or
after it.

## 5. Why the check is "try to insert, and catch the failure" rather than "look first, then insert"

It's tempting to write this as two steps: first check whether a row already exists for this message id,
and only insert if it doesn't. This has a genuine, exploitable gap: two deliveries of the same message
arriving at nearly the same moment (a real possibility — a rebalance can hand the same message to two
different consumer instances briefly, or a slow first attempt can still be running when a redelivery
fires) could BOTH check, BOTH see "not yet processed," and BOTH proceed — the exact race this whole
guard exists to prevent, just moved one layer up instead of eliminated.

Attempting the insert directly and letting the database's own primary key constraint be the judge closes
that gap completely. The database itself only ever lets ONE of two simultaneous inserts for the same key
succeed — that's what a primary key constraint means. There is no gap between "check" and "act" because
there is no separate check: the insert attempt IS the check, and PostgreSQL is what actually enforces
it, not a`race condition-prone comparison in application code.

## 6. What the method needs to do, concretely

1. Build a new `ProcessedMessage` for `(messageId, IdempotencyGuard.CONSUMER_NAME)`.
2. Try to save it via `processedMessageRepository.save(...)`.
3. If that succeeds without throwing: return `true`. This is a genuinely new message.
4. If it throws `DataIntegrityViolationException` — the exception Spring translates a primary-key
   violation into: return `false`. This message has already been handled.
5. Any OTHER exception (a connection failure, a timeout) must NOT be caught here. It needs to propagate
   all the way up so the listener treats it as "couldn't tell," not as "already handled" — those are
   two completely different situations, and confusing them is exactly the mistake
   `contracts/inventory-consumer.md`'s own failure-routing table exists to prevent.

## 7. The self-contention trap, one more time, very concretely

If you're tempted to call this guard AFTER attempting the seat hold instead of before ("check idempotency
only if we're actually about to do something"), stop and reread section 3 above. The guard has to run
FIRST. A redelivery that reaches the Redis hold before this guard has a chance to say "already handled"
will successfully re-acquire its own seats (by design — that's what makes ONE legitimate retry safe) and
then try to write a duplicate reservation. Order matters here, and it is not a matter of taste.

## 8. Verifying it

```bash
./mvnw -pl inventory-service -am verify -Dit.test=IdempotencyIT -Dfailsafe.failIfNoSpecifiedTests=false
```

Three tests, one per guarantee this method is responsible for. All three need to be green — that's the
actual definition of "done" here.
