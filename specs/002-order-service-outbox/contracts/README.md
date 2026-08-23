# Contracts: `002-order-service-outbox`

| File | What it fixes |
|---|---|
| [`orders-api.yaml`](./orders-api.yaml) | The HTTP surface: `POST /api/orders`, `GET /api/orders/{orderId}`, and the RFC 7807 shapes that keep a capacity refusal distinguishable from a bad request. |
| [`outbox-relay.md`](./outbox-relay.md) | The interface of the one method the developer implements, stated as guarantees and their tests. |

The **message** contracts are not here. `OrderCreated` and its six siblings were frozen in
[`001-event-contracts-foundation/contracts/`](../../001-event-contracts-foundation/contracts/) and
this step publishes `OrderCreated` unchanged. If a message shape needs to change, that is a versioned
change against step 1's contracts, not an edit made here.
