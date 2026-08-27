# Contracts: `003-inventory-seat-locks`

| File | What it fixes |
|---|---|
| [`seat-lock-scripts.md`](./seat-lock-scripts.md) | The two Lua scripts the developer implements, stated as guarantees and their tests. |
| [`inventory-consumer.md`](./inventory-consumer.md) | What consuming `order.created` obliges this service to do — the order of operations, the failure routing, and why the ordering is load-bearing. |

There is **no HTTP contract** for this service. It exposes nothing beyond `/actuator/health` and
`/actuator/prometheus`. Availability is the read model's job in step 6; a query endpoint here would
create a second answer to the same question before the intended one exists.

The **message** contracts are not here either. `OrderCreated`, `SeatsReserved` and `SeatsRejected` were
frozen in
[`001-event-contracts-foundation/contracts/`](../../001-event-contracts-foundation/contracts/) and this
step consumes and publishes them unchanged. If a message shape needs to change, that is a versioned
change against step 1's contracts, not an edit made here.

The outbox relay's contract is likewise unchanged from
[`002-order-service-outbox/contracts/outbox-relay.md`](../../002-order-service-outbox/contracts/outbox-relay.md).
This service ports that mechanism rather than re-specifying it; its twelve guarantees apply here
verbatim, with `aggregate_id` again being the order id and `event_type` being `seats.reserved` or
`seats.rejected`.
