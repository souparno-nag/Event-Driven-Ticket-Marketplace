# Contract: the outbox relay

**Feature**: `002-order-service-outbox`

This is the interface specification for the one method left unimplemented in this step. Everything
around it — the schema, the entity, the repository query, the scheduling, the metrics, and the tests
that judge it — ships working. The method body is written by the developer.

A step-by-step guide to writing it, pitched at someone meeting the pattern for the first time, is
delivered with the implementation task in `docs/tasks/`. This document is the contract: what the
method must guarantee, not how to arrive at it.

---

## Signature

```java
@Scheduled(fixedDelayString = "${outbox.relay.poll-interval-ms:500}")
@Transactional
public void pollAndPublish() {
    // TODO(developer)
}
```

The annotations are part of the contract and are already in place:

- `@Scheduled(fixedDelay…)` — a *fixed delay* measures from the end of one run to the start of the
  next, so a slow run can never overlap the one behind it. `fixedRate` would.
- `@Transactional` — the row locks taken by the claim query are held for the transaction's duration.
  Without this annotation the locks are released the instant the query returns and the exclusivity
  guarantee (FR-012) evaporates silently, with no error anywhere.

---

## What the method is given

| Collaborator | Provides |
|---|---|
| `OutboxRepository#claimBatch(int limit)` | The claim query of R2, already written and tested. Returns rows this relay now owns exclusively for the rest of the transaction. |
| `KafkaTemplate<String, String> kafkaTemplate` | Configured with `acks=all`, idempotent producer, `StringSerializer` for both key and value. |
| `Propagator propagator` | Micrometer Tracing. Injects a stored trace context into outgoing headers. |
| `OutboxMetrics metrics` | `recordPublished()`, `recordSendFailure()`. The two gauges read the database themselves and need no calls. |
| `int maxAttempts` | From `outbox.relay.max-attempts`, default 5. |

---

## Guarantees the method must provide

Each is exercised by a named test. The method is done when they pass.

| # | Guarantee | Requirement | Test |
|---|---|---|---|
| 1 | Every claimed row is sent to the channel named by its `event_type`. | FR-011 | `OutboxRelayIT#publishesPendingRecord` |
| 2 | The Kafka message key is the row's `aggregate_id`. | FR-018 | `OutboxRelayIT#keysMessageBySagaId` |
| 3 | The message value is the stored `payload`, sent verbatim — not re-serialized. | FR-010 | `OutboxRelayIT#sendsStoredPayloadUnchanged` |
| 4 | A row is marked `PUBLISHED` with `published_at` set **only after** the broker acknowledges. | FR-017 | `OutboxRelayIT#marksPublishedOnlyAfterAck` |
| 5 | A row already `PUBLISHED` is never sent again. | FR-011 | `OutboxRelayIT#doesNotResendPublished` |
| 6 | On send failure the row stays `PENDING`, `attempts` increments, `last_error` records why. | FR-016, FR-028 | `OutboxRelayIT#retainsFailedRecordForRetry` |
| 7 | When `attempts` reaches `maxAttempts` the row becomes `PARKED` and is never retried. | FR-029 | `OutboxRelayIT#parksAfterMaxAttempts` |
| 8 | One row's failure does not abandon the other rows in the batch. | FR-016 | `OutboxRelayIT#oneFailureDoesNotStopTheBatch` |
| 9 | The stored trace context is injected into the outgoing message's headers. | FR-026 | `OutboxTracingIT#continuesTheOriginalTrace` |
| 10 | A row with no stored trace context is still sent, without headers and without error. | FR-027 | `OutboxTracingIT#sendsUntracedRecord` |
| 11 | Two relays running concurrently never send the same row. | FR-012 | `OutboxConcurrencyIT#noRecordSentTwice` |
| 12 | Rows for one order reach the channel in recording order. | FR-014 | `OutboxOrderingIT#preservesPerOrderOrder` |

Guarantees 11 and 12 are provided largely by the claim query rather than by the method body; they are
listed because the method can still break them — by sending asynchronously without awaiting the
acknowledgement, or by committing marks outside the claim transaction.

---

## Traps this contract exists to prevent

Each of these produces a system that looks healthy and is not.

**Marking sent before the acknowledgement arrives.** `kafkaTemplate.send()` returns a future
immediately. Marking the row on that return converts at-least-once into at-most-once: rows are ticked
off that never reached the broker, and no error is ever raised. The acknowledgement must be awaited.

**Catching an exception around the whole batch.** One bad row then abandons every row behind it,
including rows for unrelated orders. Failure is per row.

**Incrementing `attempts` outside the failure path.** Incrementing on every attempt including
successful ones parks healthy rows after five ordinary sends.

**Swallowing the failure entirely.** A row that fails silently and stays `PENDING` with `attempts`
unchanged is retried forever — precisely the behaviour the parking decision was taken to eliminate.

**Re-serializing the payload.** The payload was serialized when the row was written, deliberately.
Parsing and re-emitting it reopens the drift FR-010 closes, and re-introduces the `1E+2` money bug
that `WRITE_BIGDECIMAL_AS_PLAIN` was set to prevent.

---

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `outbox.relay.poll-interval-ms` | `500` | Delay between runs. Sets the SC-004 two-second budget's floor. |
| `outbox.relay.batch-size` | `100` | Rows claimed per run (FR-015). Bounds the transaction. |
| `outbox.relay.max-attempts` | `5` | Failures before a row is parked (FR-029). |
