# T082 — `OrderAcceptanceService`, the one place both tables are written

**What this task did:** wrote the transactional method that is this entire build step's reason for
existing — the order and its outbox row, written together, or neither at all.

```java
@Transactional
public UUID acceptOrder(CreateOrderRequest request) {
    UUID orderId = UUID.randomUUID();

    Order order = new Order(orderId, request.userId(), request.showId(),
            new LinkedHashSet<>(request.seatIds()), request.amount());
    orderRepository.save(order);

    OutboxRecord outboxRecord = outboxWriter.writeOrderCreated(order);
    outboxRepository.save(outboxRecord);

    return orderId;
}
```

---

## `@Transactional`, and what it actually buys here

One annotation wraps everything below it in a single database transaction: both `save` calls succeed
together, or — if anything after the first `save` throws — PostgreSQL undoes both, as though neither
had happened. This is what makes "the order exists without its notification" or "a notification for
an order that doesn't exist" impossible rather than merely unlikely.

`OrderAcceptanceIT`'s rollback test (T075) proves this directly: it makes the *second* save fail on
purpose and checks that the *first* one — the order — is gone too, not left behind as an orphan.

## Why this is deliberately the only place either repository is called from

No other class in this service calls `orderRepository.save(...)` or `outboxRepository.save(...)`.
That is not an accident of how the code was organized — it is what makes the atomicity claim
**reviewable**. FR-007 says the two writes must be atomic; with exactly one method that ever performs
either write, checking that claim means reading one method, not searching the whole codebase for
every place that might touch these tables.

## The order the two writes happen in, and why it is not arbitrary

The order is saved first, then the outbox row — never the reverse. `OutboxRecord` needs the order's
identifier (`order.getId()`) to know which order it concerns, so the order object has to exist before
the outbox row can be built at all. This isn't a stylistic preference; the second write is
structurally dependent on the first having already happened.

## Nothing is sent here

There is no call to Kafka anywhere in this method, and there should never be one added. Sending is
the relay's job — `OutboxRelay`, built by hand in T099 — running *after* this transaction has already
committed. Publishing from inside this method would reopen exactly the gap the outbox pattern exists
to close: a message that looks sent but whose surrounding transaction later rolls back, or a
transaction held open waiting on a slow broker response it has no business waiting on.

## The identifier is generated here, before either row exists

```java
UUID orderId = UUID.randomUUID();
```

Generating it in the application, rather than letting the database assign one, is what lets the same
value be used as the order's primary key, the outbox row's `aggregate_id`, and — eventually — the
Kafka partition key, all decided at the moment this method starts, before either table has been
touched.

---

## Confirmed

`OrderAcceptanceIT`'s three scenarios all pass: the straightforward one-order-one-outbox-row case,
200 concurrent acceptances producing 200 of each with no collisions, and the forced-failure rollback.
Together with the `OutboxRecord` fix from T081, this is the first point in the build where the
central promise of this whole feature — order and outbox, together or not at all — is proven against
a real database rather than merely designed.
