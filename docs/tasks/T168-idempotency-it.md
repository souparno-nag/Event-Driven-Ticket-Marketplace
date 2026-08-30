# T168 — `IdempotencyIT`

**What this did:** wrote the test for SC-006 before any of the consumer code it will eventually judge
exists — three scenarios proving that a message arriving more than once, which Kafka's own
at-least-once delivery promise makes an ordinary, expected occurrence rather than a bug, changes the
world exactly once.

---

## What "at-least-once delivery" actually means, and why the consumer has to be the one who fixes it

Kafka promises every message is delivered *at least* once — never zero times, but possibly more than
once, whenever there's any doubt about whether an earlier delivery actually finished (a consumer
restarting, a slow network, a rebalance). The producer side (order-service, since step 2) can't fix
this on its own: it has already done its job the moment the message is safely on the topic. Whatever
happens to that message afterward — read once, read twice, read because a broker had a moment of doubt
— is the CONSUMER's problem to solve, because only the consumer is in a position to notice "I've
already done this."

## Why publishing the same message ten times is a faithful test of "redelivery"

It would be tempting to think a real redelivery test needs to actually force Kafka to redeliver — kill
a consumer mid-rebalance, say. That's both hard to engineer reliably and unnecessary: from the
consumer's own point of view, at-least-once delivery already looks exactly like "the same `messageId`
showing up as more than one distinct record on the topic." Publishing the identical `OrderCreated`
payload ten separate times produces precisely that shape, entirely under this test's own control,
without depending on timing nobody can force on demand.

## Why the assertions check more than "did booking succeed"

A test that only waited for `seats.reserved` to appear and then declared victory would pass against a
consumer with NO idempotency guard at all — the very first of the ten deliveries would produce that
outcome regardless. What actually proves the other nine didn't each duplicate it is checking the
database directly afterward: exactly one `reservations` row, exactly one live claim on the seat,
exactly one `processed_messages` row for this message id, exactly one `outbox` row. Four separate
counts, because a guard that gets even one of them wrong is still broken in a way "an outcome
eventually appeared" would never catch.

## The interpretation this test makes explicit: what "an interrupted delivery" means for an automated test

The contract names three guarantees; the second is the trickiest to test honestly. "An interrupted
delivery still produces its outcome on redelivery" could mean forcing the very first delivery to fail
partway through — which isn't something a black-box test can do to a consumer that doesn't yet have any
injectable failure point, without instrumenting the service's internals in a way that would stop
testing anything but the test's own scaffolding. This test instead reproduces what an interruption
looks like from the OUTSIDE: a message whose first delivery has already fully succeeded and been
announced, arriving again anyway — the exact shape a real interruption between "the work finished" and
"the offset committed" produces. The guarantee under test is that this late redelivery is recognised as
already handled and produces no error and no second effect, which is what a correct guard must do
regardless of why the redelivery happened.

## Verifying it — and why every assertion currently fails the same way

```text
Tests run: 3, Failures: 0, Errors: 3
```

All three time out waiting for `seats.reserved` — `OrderCreatedListener` (T178) doesn't exist yet, so
nothing in this service consumes `order.created` at all. That is this checkpoint's correct, honest
state, identical to what T163 and T167 already recorded for the quickstart scenarios blocked on the
same missing piece. This test will start passing the moment the consumer and the idempotency guard
(T172–T178) exist, with no changes needed to the test itself.
