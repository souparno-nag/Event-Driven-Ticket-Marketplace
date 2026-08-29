# T121 — The processed-messages migration

**What this task did:** wrote `V3__create_processed_messages.sql`, the table that makes this service
safe to run against a message channel that can deliver the same message more than once.

It's a small file — one table, six lines. The reasoning behind its shape is not small, and the
original project brief actually got that shape wrong in a way worth understanding.

---

## The problem this table exists to solve

Step 2 of this project established something important and slightly uncomfortable: Kafka, as used
here, delivers messages **at least once**, not **exactly once**. A message can arrive twice — after a
consumer restarts mid-processing, after a rebalance moves a partition to a different consumer instance,
or because whatever published it retried a send whose acknowledgment got lost even though the send
itself succeeded. That decision wasn't a mistake to fix later; it was accepted deliberately, on the
condition that whoever *consumes* a message takes responsibility for not reacting to it twice.

This service is the first consumer in the whole project. This table is where that promise gets kept.

Without it, a redelivered `order.created` message would cause this service to try holding the same
seats a second time — for an order that, from this service's point of view, looks exactly like a brand
new request. Best case, the seats are still held by the first attempt and the redelivery gets refused
for seats that are actually its own, which is confusing and wrong. Worse case, if the timing lines up
badly, it could create two reservations for what was really one order.

---

## The table

```sql
CREATE TABLE processed_messages (
    message_id     UUID          NOT NULL,
    consumer_name  VARCHAR(64)   NOT NULL,
    processed_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),
    PRIMARY KEY (message_id, consumer_name)
);
```

The mechanism itself is almost embarrassingly simple: before doing anything else with a message, insert
a row identifying it. If the insert succeeds, this is the first time this message has been seen —
proceed. If the insert fails because the row already exists, this message has already been handled —
stop, having changed nothing. (The actual guard code that does this insert-or-skip arrives later, in
`IdempotencyGuard`, T172 — deliberately left as a stub for the developer to implement by hand, the same
way the Lua scripts are.)

What's interesting about this table isn't the mechanism — it's that the project's original brief
described a table shape that would have made this mechanism *fail silently* the moment a second
consumer joined the picture, and getting it right meant deviating from that brief in two specific ways.

---

## Deviation one: the primary key has to include the consumer

The brief called for:

```sql
processed_events(event_id UUID PRIMARY KEY, consumer_name, processed_at)
```

— a single-column primary key on the message's own id, with `consumer_name` demoted to an ordinary,
unenforced column sitting alongside it.

Here's why that's broken, and why the breakage is easy to miss at first glance. With `message_id`
alone as the primary key, the first insert for a given message **claims that id for the entire
table**, regardless of which consumer wrote it. Imagine a second consumer — reading a *different*
Kafka topic, doing completely unrelated work — happens to process a message that carries the same
`message_id` (unlikely today, since messages are UUID-keyed per event, but the point is the *shape* of
the bug, not this specific scenario). Or, more realistically for this project's future: today,
`inventory-service` is the only consumer of `order.created`. But nothing rules out a second consumer of
the same topic arriving in a later step — some analytics or auditing service, say. The moment that
happens, whichever consumer's insert lands *first* silently locks the second one out. Not with an
error. Not with a log line. The second consumer's insert just violates the primary key, gets treated as
"already processed," and its handler never runs — for a message it has genuinely never seen before.

That failure mode is genuinely nasty to debug, because everything *looks* healthy. No exception
surfaces anywhere obviously wrong; the symptom is just "this one handler mysteriously never seems to
fire," discovered days or weeks after the second consumer was added, by someone who has no reason to
suspect a completely unrelated service's idempotency table.

Making the key composite — `PRIMARY KEY (message_id, consumer_name)` — fixes this by construction.
Two different consumers processing the same message now produce two entirely distinct primary key
values, so they can never collide with each other, no matter how many consumers eventually read from
however many topics.

---

## Deviation two: it isn't called `event_id`

The brief also named the message-identity column `event_id`. That collides with a naming decision this
project's very first build step made on purpose: the shared contract module stopped using the word
"event" to mean "message," specifically because it was doing double duty — sometimes meaning *this
message that arrived*, sometimes meaning *the concert someone is buying tickets to*. That ambiguity got
resolved by renaming: a concert is a `showId`, and a message's own identity is `messageId`.

Naming this column `event_id` would reintroduce precisely that ambiguity, and it would do it in the
single table whose entire reason for existing is identifying *messages, not shows*. So the column here
is `message_id`, matching the contract's own field name exactly.

---

## Why there's no index beyond the primary key

Every single query this table will ever receive is the same shape: "does a row exist for exactly this
`(message_id, consumer_name)` pair?" — the guard's own insert-or-conflict check. That's precisely what
a primary key already indexes for free. Adding a secondary index here would cost write overhead on
every insert for a lookup pattern nothing will ever actually use.

---

## Verifying it

Applied directly against a real PostgreSQL 16 instance (the project's own container, in a scratch
`inventory` schema, dropped afterward), then deliberately tested against exactly the failure mode the
composite key exists to prevent:

```text
NOTICE:  OK: same message_id, two consumer_names -- both inserts succeeded
NOTICE:  OK: redelivery of the same (message_id, consumer_name) pair was rejected
```

The same `message_id` was inserted under two different `consumer_name` values and both succeeded —
confirming the fix for deviation one actually works, not just that the brief's bug was correctly
identified in prose. Then the identical `(message_id, consumer_name)` pair was inserted a second time
and rejected with `unique_violation` — confirming ordinary redelivery detection still works with the
wider key. The full guard behavior — that a rejected insert also leaves the *rest* of that
transaction's work rolled back, not merely this one row — belongs to `IdempotencyIT` (T168), once
`IdempotencyGuard` (T172) exists to drive it end to end.
