# A guide to writing `OutboxRelay.pollAndPublish()`

This is the one method in this entire build step you write by hand. Everything around it — the
schema, the entity, the claim query, the Kafka producer, the metrics, the scaffolding, and every
test that will judge it — already exists. This document is not a spec (that's
`contracts/outbox-relay.md`, and you should have it open alongside this). It's a walk-through: what
the pattern is, what you've been handed, and what tends to go wrong, aimed at someone meeting all of
this for the first time.

---

## 1. What problem this method is solving, one more time

Two paragraphs back in `docs/tasks/T063-outbox-migration.md` explain why the `outbox` table exists
at all, but the short version, because it's worth having fresh in your head before you write this:

Accepting a booking needs two things to happen — store the order, and tell the rest of the system it
exists. Those are two different systems (a database, a broker), and you cannot commit to both at
once. So this project doesn't try to. `OrderAcceptanceService` writes the order **and** a row saying
"a message needs sending" to the same database, in one transaction. That part is already built and
already working — you don't need to touch it.

What's missing is the other half: something that actually reads those "needs sending" rows and sends
them. That's this method. It runs on a timer, every half second by default, and each time it runs it
should:

1. Find some rows that haven't been sent yet.
2. Send each one to Kafka.
3. Mark each one as sent — but only the ones that actually made it.

That's the whole job. The difficulty is entirely in doing each of those three steps correctly under
things going wrong — a message that fails to send, two copies of this relay running at once, a
restart in the middle of everything.

---

## 2. What you've been given, and how to use each piece

```java
public OutboxRelay(
        OutboxRepository outboxRepository,
        KafkaTemplate<String, String> kafkaTemplate,
        Propagator propagator,
        OutboxMetrics metrics,
        @Value("${outbox.relay.batch-size:100}") int batchSize,
        @Value("${outbox.relay.max-attempts:5}") int maxAttempts) {
```

**`outboxRepository.claimBatch(batchSize)`** — call this first. It returns a `List<OutboxRecord>`:
rows that are now *yours*, exclusively, for the rest of this method call. Nobody else — not another
copy of this same relay running on another thread, not a second instance of this service — can also
be handed these same rows while your transaction is open. You don't need to think about locking at
all; it's already done by the time this list comes back to you. The rows also already come back in
the order you need to send them in. Your job is just to go through the list, in order, and process
each one.

**`kafkaTemplate.send(topic, key, value)`** — this is how you actually put a message on a channel.
It returns a `CompletableFuture` (or a similar future-like type, depending on the exact Spring Kafka
version — check what your IDE shows you). **This is the single most important thing to get right in
this whole method: you must wait for that future to complete before you decide what happened.**
Calling `.send(...)` starts the send; it does not tell you whether it worked. If you call `send()`
and immediately mark the row as sent without waiting for the result, you have built something that
*looks* correct in every quick test and *lies* the first time a send genuinely fails — the row says
"sent," Kafka never got it, and nobody will ever know, because nothing raised an error. Look at
`.get()` (blocks and throws if the send failed) or a similar blocking-and-throwing method on
whatever future type your Spring Kafka version gives you.

**`propagator`** — this is how you attach a stored trace to an outgoing message's headers. You'll
need a `Map<String, String>` (or similar) to act as the "carrier" the propagator writes into, and
then you turn that map into actual Kafka message headers before sending. The row's `traceparent` and
`tracestate` fields (from `OutboxRecord`) are what you feed the propagator — but only if they're not
null. A row with no stored trace context (nothing was active when it was written) should still send
successfully, just without any trace headers attached.

**`metrics.recordPublished()`** and **`metrics.recordSendFailure()`** — call these at the moment
each outcome actually happens, once per row. That's it — the two gauges (`outbox.records.parked`,
`outbox.oldest.pending.age.seconds`) need nothing from you at all; they read the database themselves.

**`maxAttempts`** — compare a row's `attempts` count against this after a failure. Once it's reached,
the row should be parked instead of retried again.

---

## 3. The twelve guarantees, in plain language

`contracts/outbox-relay.md` lists these formally. Here's what each one actually *means*, in the order
you'll naturally run into them while writing the method.

1. **Every claimed row gets sent to the channel named in its `event_type` column.** Not a hardcoded
   channel — read it from the row.
2. **The Kafka message key is the row's `aggregate_id`.** This is what makes Kafka's own partitioning
   put all of one order's messages on the same partition, which is the whole mechanism per-order
   ordering rests on further downstream.
3. **The message body is the row's stored `payload`, exactly as it is — never re-parsed and
   re-written.** `payload` is already a `String`. Send that string. Don't touch it.
4. **A row only becomes `PUBLISHED` after the broker has actually acknowledged it** — meaning after
   you've waited for the send's future and it completed without throwing.
5. **A `PUBLISHED` row is never sent again.** You get this one for free — `claimBatch` never returns
   rows that are already `PUBLISHED`. Just don't go looking for them yourself.
6. **A failed send leaves the row `PENDING`, with `attempts` incremented and `last_error` recording
   why.** Catch the exception the future threw, and call the two mutator methods `OutboxRecord`
   already gives you for this (`recordFailure(String error)`).
7. **Once `attempts` reaches `maxAttempts`, the row becomes `PARKED` and stops being retried.** Check
   this right after incrementing attempts on a failure — `OutboxRecord.park()` is already there for
   you to call.
8. **One row's failure must not stop the rest of the batch.** This means: whatever loop you write
   over the claimed rows, a failure on row 3 must not prevent rows 4, 5, 6... from still being
   attempted. Think carefully about where your `try`/`catch` goes.
9. **A row's stored trace context ends up in the outgoing message's headers.** Covered above.
10. **A row with no stored trace context still sends — untraced, no error.** Also covered above —
    just remember to check for null before asking the propagator to do anything.
11. **Two relays never send the same row.** You don't implement this — `claimBatch` already provides
    it — but you *can* break it, specifically by sending asynchronously and moving on to the next row
    before the current one's send has actually finished. Waiting for each future, one at a time,
    before you touch the next row, is what keeps this true.
12. **Rows for one order arrive in the order they were recorded.** Also mostly `claimBatch`'s doing.
    You can break this one the same way as #11 — by not respecting the order the list came back in,
    or by sending several rows concurrently without waiting for each to finish before starting the
    next.

---

## 4. The five traps — what each one actually looks like when you've made it

These are all things that will compile, run, and *appear* to work the first few times you test them
by hand. That's what makes them traps.

**Marking sent before the acknowledgement arrives.** Symptom: everything looks fine locally. The
moment a network hiccup or a broker restart causes a real send failure, you'll find `PUBLISHED` rows
in the database whose messages never actually reached Kafka — and there is no error message anywhere
telling you this happened, because nothing checked. This is the single easiest mistake to make,
because `.send()` *looks* synchronous if you don't look closely at its return type.

**Catching an exception around the whole batch instead of around each row.** Symptom: one bad row —
maybe one with a malformed payload, or a channel that doesn't exist — causes *every other row in that
batch* to silently stop being processed too, including rows belonging to completely unrelated,
perfectly healthy orders. Nothing crashes. The backlog just quietly stops draining, and it looks like
the relay itself has stalled rather than like one specific row is the problem.

**Incrementing `attempts` on every call, including successful ones.** Symptom: rows that are sending
successfully every single time nonetheless get parked after five ordinary, uneventful sends, because
something is counting *every* attempt rather than only the *failed* ones.

**Swallowing a failure instead of recording it.** Symptom: a row that can never be sent — say, a
channel name that will never exist — stays `PENDING` forever, `attempts` never moves, and it gets
retried on every single poll, forever, quietly wasting work and (per FR-030) blocking every later row
for its own order, with nothing in the database ever explaining why.

**Re-serializing the payload instead of sending it as stored.** Symptom: this one is the sneakiest,
because it usually still *works* — most of the time, parsing JSON and writing it back out produces
identical JSON. The failure shows up specifically for money amounts: `BigDecimal` can round-trip
through a generic JSON library and come back out in scientific notation (`1E+2` instead of `100.00`),
which is exactly the bug `WRITE_BIGDECIMAL_AS_PLAIN` (T070) was set up to prevent — and re-parsing
the payload here bypasses that setting entirely, since you'd be using a different mapper (or none at
all) than the one that wrote it.

---

## 5. A rough shape, not a solution

Something like this, in spirit — deliberately not filled in, since working out the exact code is the
point:

```java
@Scheduled(fixedDelayString = "${outbox.relay.poll-interval-ms:500}")
@Transactional
public void pollAndPublish() {
    List<OutboxRecord> claimed = outboxRepository.claimBatch(batchSize);

    for (OutboxRecord record : claimed) {
        try {
            // build headers from record.getTraceparent()/getTracestate(), if present
            // send via kafkaTemplate, using record.getEventType(), record.getAggregateId(), record.getPayload()
            // WAIT for the result
            // on success: record.markPublished(...), metrics.recordPublished()
        } catch (Exception e) {
            // record.recordFailure(...), metrics.recordSendFailure()
            // if record.getAttempts() has reached maxAttempts: record.park()
        }
    }
}
```

You don't need to call `outboxRepository.save(record)` after mutating it — inside a
`@Transactional` method, JPA tracks changes to entities it already loaded (this is called "dirty
checking") and writes them automatically when the transaction commits. If that sentence doesn't mean
much yet, it will make more sense once you see it working; for now, just know you don't need an
explicit save call for rows `claimBatch` already handed you.

---

## When you're done

Run:

```bash
./mvnw -pl order-service -am verify -Dit.test=OutboxRelayIT,OutboxTracingIT,OutboxConcurrencyIT,OutboxOrderingIT,OutboxRestartRecoveryIT -Dfailsafe.failIfNoSpecifiedTests=false
```

All five files, all their tests, need to be green. That's the actual definition of "done" here — not
whether it looks right, but whether it passes the twelve guarantees this method promised to keep.
