# T024 — `OrderConfirmed`, the happy ending

**What this task did:** created `OrderConfirmed.java` — the saga's successful terminal message.

---

## Where it sits

```
OrderCreated ──► SeatsReserved ──► PaymentSucceeded ──► OrderConfirmed
                                                              │
                                        ┌─────────────────────┴──────────────────┐
                                        ▼                                        ▼
                          inventory commits the hold                projection updates
                          into a durable reservation                the read model
```

Order-service publishes it after moving the order to `CONFIRMED`. Two services consume it, and that
fan-out is the clearest illustration in this project of why choreography was chosen: order-service
does not know projection-service exists. A third consumer — emailing a ticket, say — can subscribe
later with no change to any existing service.

**Terminal and absorbing.** Nothing follows it and nothing reopens it. A refund or a customer
cancellation would be a *new* process with its own messages, not a continuation of this saga.

---

## Why the seats are repeated

`SeatsReserved` already listed them. Why say it again?

Because a consumer must be able to act on **this message alone**.

Consider what the alternative requires. If `OrderConfirmed` carried only `orderId`, then to know
which seats to commit, inventory-service must have seen the earlier `SeatsReserved` and remembered
it. That is fine for a process that has been running, in order, without interruption, forever. It
breaks for:

- **A consumer that joined later.** Projection-service is added in build step 6, long after orders
  have been flowing.
- **A replay.** Reprocessing a channel to rebuild a read model means reading messages whose
  predecessors are not being replayed.
- **A restart mid-saga.** Whatever was held in memory is gone.

This is the difference between a message that is a **fact** and a message that is a **delta**. A
fact stands alone: "order X is confirmed, for seats A12 and A13". A delta only means something
relative to state you already hold. Deltas are smaller and they are a trap, because the coupling
they create — every consumer must have seen everything before — is invisible until the day something
restarts.

The cost is duplication across messages, and it is the right trade for the same reason `userId` is
carried on `OrderCreated`: a message is a historical record, and it should still make sense when read
cold.

---

## The read-model consumer, and eventual consistency

Projection-service consumes this and updates an Elasticsearch document that
`GET /api/availability/{id}` serves. Which introduces a property worth naming plainly:

**Between order-service publishing this message and the read model being updated, the two disagree.**
The order is confirmed; the availability endpoint still shows those seats as free. Milliseconds,
usually. Not zero.

That is **eventual consistency**, and it is the price of a separate read model (**CQRS** — Command
Query Responsibility Segregation, where the thing you write to and the thing you read from are
different stores). What you buy is a read model shaped for querying and scaled independently of the
write path. What you pay is a window where a reader sees stale data.

The engineering question is never "how do I eliminate the window" — you cannot, short of giving up
the separate read model — but **"what is safe to do with a possibly-stale read?"** Showing a seat
map: fine. Deciding whether a seat is available to sell: absolutely not, and this system never does
— that decision is made by the Redis seat lock in inventory-service, which is the authority.

The README is required to state this window explicitly. Systems get into trouble when the
availability of a stale read is a surprise rather than a documented property.

---

## Try it yourself

```bash
./mvnw -pl common-events compile
```

**Expect**: `BUILD SUCCESS`, 12 source files. One record to go.

---

## What comes next

**T025** — `OrderCancelled`, the other terminal message and the last record. When it lands, the test
files compile for the first time and all four run.
