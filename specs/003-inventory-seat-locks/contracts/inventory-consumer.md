# Contract: consuming `order.created`

**Feature**: `003-inventory-seat-locks`

This service is the project's first message consumer. Step 2 established at-least-once delivery and
deliberately left duplicate suppression to whoever consumed the messages. This document states what
that obligation actually is, and what the listener owes the rest of the saga.

---

## The channel

| | |
|---|---|
| Subscribes | `order.created` (`Topics.ORDER_CREATED`) — 3 partitions |
| Consumer group | `inventory-service` |
| Dead-letter | `order.created.DLT` (`Topics.dlt(Topics.ORDER_CREATED)`) |
| Publishes | `seats.reserved`, `seats.rejected` — via the outbox, keyed by saga id |

---

## The order of operations, which is itself the contract

```text
1. deserialize                          ─┐ failure here is NOT retryable → DLT
2. check schemaVersion is recognised    ─┘

3. ── BEGIN TRANSACTION ────────────────────────────────────────────
4.   insert processed_messages(messageId, "inventory-service")
        └─ duplicate key? → already handled, commit and stop  (FR-030)
5.   look up show → not found?  reject SHOW_NOT_FOUND
6.   look up seat labels → any missing? reject SEATS_NOT_FOUND
7.   retire any lapsed reservation covering these seats        (FR-018)
8.   EVAL lock_seats.lua
        └─ returned 0? → reject SEATS_ALREADY_HELD
9.   insert reservation + reservation_seats                    (HELD)
10.  insert outbox row: SeatsReserved or SeatsRejected
11. ── COMMIT ──────────────────────────────────────────────────────

12. relay publishes, later and independently
```

**Step 4 comes before step 8, and that ordering is load-bearing** (FR-032). Run the guard after the
lock attempt and a redelivered request contends with the hold it already owns, gets `0` back, and
announces `SEATS_ALREADY_HELD` for seats that are its own. The saga is then told its seats are gone by
the only service that knows they are not.

**Step 7 comes before step 8 for a subtler reason** (R6). Redis frees a seat the instant its TTL
lapses, but the previous reservation is still `HELD` in PostgreSQL. Without retiring it in this same
transaction, the unique index rejects a booking Redis just granted — and correctness would depend on a
background sweeper having run recently, which is not testable.

**Step 8 is inside the transaction but is not transactional.** Redis has no rollback. If the
transaction fails after the script succeeds, the seats stay held until their TTL lapses. That is the
accepted direction of failure: seats briefly unavailable, never double-sold. The reverse order —
database first, Redis second — fails the other way and is therefore wrong.

---

## Guarantees

| # | Guarantee | Requirement | Test |
|---|---|---|---|
| 1 | Every consumed request yields exactly one outcome message | FR-022 | `SagaEndToEndIT#producesOneOutcome` |
| 2 | The outcome is keyed by the order's saga id | FR-024 | `SagaEndToEndIT#keysBySagaId` |
| 3 | A redelivery produces no second hold and no second reservation | FR-030 | `IdempotencyIT#tenDeliveriesOneEffect` |
| 4 | A redelivery of an interrupted delivery still produces its outcome | FR-031 | `IdempotencyIT#outcomeSurvivesInterruption` |
| 5 | Two different messages never suppress one another | FR-029 | `IdempotencyIT#distinctMessagesAreIndependent` |
| 6 | An unrecognised `schemaVersion` is dead-lettered, never processed or discarded | FR-003 | `UndecidableRequestIT#unknownVersionGoesToDlt` |
| 7 | A store outage produces **no** outcome message | FR-047 | `UndecidableRequestIT#noFalseRefusalWhileDown` |
| 8 | After the outage, every affected request is decided with no manual step | FR-049 | `UndecidableRequestIT#recoversWithoutReplay` |
| 9 | An undecidable message reaches the DLT within its attempt limit | FR-048 | `UndecidableRequestIT#dlttedAtAttemptLimit` |
| 10 | Listeners do not start until the Redis rebuild has completed | FR-015 | `SeatLockRebuildIT#rebuildPrecedesConsumption` |

---

## Failure routing

Two classes, deliberately routed differently (R9).

| Failure | Retryable? | Result |
|---|---|---|
| Redis or PostgreSQL unreachable | **Yes** | backoff, redeliver, DLT at the attempt limit |
| Transaction timeout | **Yes** | as above |
| Optimistic lock failure | **Yes**, once inline (FR-013), then as above | never a seat refusal |
| Unknown `schemaVersion` | No | DLT immediately |
| Deserialization failure | No | DLT immediately |

**No infrastructure failure is ever announced as a seat refusal.** None of the three frozen causes
means "the decision could not be made", so answering with one states something about the seats that was
never established, and permanently cancels an order that would have succeeded.

**Accepted cost**: redelivery holds up the partition, so unrelated orders behind the message wait. That
is honest back-pressure while a dependency is down, and it is exactly why the attempt count is bounded
rather than infinite.

---

## Implementation notes that are easy to get wrong

**The DLT suffix.** `DeadLetterPublishingRecoverer` defaults to `-dlt`, producing `order.created-dlt`.
Step 1 provisioned `order.created.DLT` and `Topics.dlt()` is the contract for that name. Override the
destination resolver, or messages land in an unprovisioned topic — and since the real environment sets
`auto.create.topics.enable=false`, the recovery itself then fails and the message is lost.

**Manual acknowledgement is not required and not used.** Spring Kafka commits the offset only after the
listener returns normally, so throwing is what triggers redelivery. Committing manually would add a
second place for the offset to advance past work that did not happen.

**`@Transactional` on the service method, not the listener.** The listener translates a message into a
command; the transaction belongs to the work. Annotating the listener would also enclose the error
handler's own bookkeeping.

---

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `spring.kafka.consumer.group-id` | `inventory-service` | Also the `consumer_name` written to `processed_messages` |
| `spring.kafka.listener.auto-startup` | `false` | Listeners are started by the rebuilder (R4, FR-015) |
| `inventory.consumer.max-attempts` | `4` | Deliveries before dead-lettering (FR-048) |
| `inventory.consumer.backoff-ms` | `500` | Initial backoff, doubling |
