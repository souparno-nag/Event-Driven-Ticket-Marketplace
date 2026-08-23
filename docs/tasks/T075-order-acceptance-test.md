# T075 — Specifying the atomicity guarantee, before the service exists

**What this task did:** wrote the test for the single most important property this build step
delivers — that the order row and its outbox row are written together or not at all — against an
`OrderAcceptanceService` that T082 has not yet created.

---

## Confirmed to fail, for the right reason

```text
OrderAcceptanceIT.java:[45,17] cannot find symbol
  symbol:   class OrderAcceptanceService
```

As with T073 and T074, this file will not compile until the class it describes exists. Extends
`PostgresIT` rather than a Kafka-backed base, deliberately: this story is about the database write,
not the send, and its tests should not pay for a broker they never touch.

## Three tests, three different ways to break atomicity

**The straightforward case.** Accept one request, then look directly in the database: exactly one
order and exactly one outbox row, sharing the same identifier. Nothing subtle here — this is the
baseline the other two tests stress.

**Concurrency (SC-001).** Two hundred requests fired at once through a thread pool, then checked: two
hundred *distinct* order identifiers (no collision), and every one of them has both its order row and
its outbox row. This is the test that would catch a bug where two concurrent writes somehow shared
state — a static field used where an instance field belonged, say.

**Forced failure (SC-002)** — the interesting one. There is no hook in the production code to inject
a failure; nothing should be built "for testability" that does not also serve the real design. Instead
this test replaces `OutboxRepository` with a Mockito mock, but only for itself:

```java
@Nested
class RollbackWhenTheOutboxWriteFails {
    @MockBean
    private OutboxRepository outboxRepository;
    ...
}
```

A `@Nested` class gets its own Spring application context. Declaring `@MockBean` there swaps the real
`OutboxRepository` for a mock *inside that context only* — the concurrency test above, running in
the outer class with the real repository, is completely unaffected. Inside the nested test, the mock
is told to throw when `save()` is called:

```java
when(outboxRepository.save(any())).thenThrow(new RuntimeException("simulated outbox failure"));
```

The order-writing half of the transaction is real — a genuine row goes to a genuine PostgreSQL
container. Only the outbox half is faked to fail. If `OrderAcceptanceService`'s method is correctly
`@Transactional`, the failure propagates out, Spring rolls the whole transaction back, and the
order that really was written a moment ago is undone along with it. The test proves this by capturing
which order id was attempted (via `ArgumentCaptor`, since the mock never returns a real saved record)
and then checking that id is genuinely absent from the database:

```java
verify(outboxRepository).save(captor.capture());
UUID attemptedOrderId = captor.getValue().getAggregateId();
assertThat(orderRepository.findById(attemptedOrderId)).isEmpty();
```

This is the test that would fail loudly if `@Transactional` were ever accidentally removed, or if the
two writes were split across two separate transactions — exactly the mistake this whole build step
exists to make impossible.

---

## A design decision this test locks in

`OrderAcceptanceService.acceptOrder(CreateOrderRequest)` is specified here to return a plain `UUID`
— the newly created order's identifier — rather than the full `Order` object or nothing at all. That
is what the HTTP layer needs in T083 to build the `202 Accepted` response's `Location` header and
body, and it is the minimum this test needs to check that the right row was written. Recorded here so
T082 lands consistent with it.
