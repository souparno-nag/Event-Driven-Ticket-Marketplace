# Phase 1 Data Model: Event Contracts & Local Foundation

This step defines wire messages only. No database schema is created — PostgreSQL, Redis, and
Elasticsearch are provisioned and health-checked, but no application table, key, or index exists
until step 2.

## Envelope

Every message carries these four components. They are repeated on each record rather than
inherited (see [research.md](./research.md) R2).

| Field | Type | Rule |
|---|---|---|
| `messageId` | `UUID` | Unique per message. Non-null. The idempotency key consumers deduplicate on from step 2. Never reused, including on republish after a failure. |
| `sagaId` | `UUID` | Equal to the `orderId` of the order this message concerns. Non-null. Also the partition key (FR-026). |
| `occurredAt` | `Instant` | When the fact happened, not when it was published. Non-null, UTC. |
| `schemaVersion` | `int` | Starts at `1`. Incremented only on a breaking change to that message type. |

**`messageId` versus `showId`**: `messageId` identifies *this message*. `showId` identifies the
concert being ticketed. They are never interchangeable. The original brief used `eventId` for
both, which is the defect this naming resolves (FR-003, SC-007). The word "event" is avoided as a
field name anywhere in the contract module.

## Shared types

```text
sealed interface SagaEvent
    permits OrderCreated, SeatsReserved, SeatsRejected,
            PaymentSucceeded, PaymentFailed, OrderConfirmed, OrderCancelled
```

Declares the four envelope accessors and nothing else — no state, no behaviour. It exists so
consumers can `switch` exhaustively over saga messages and have the compiler catch a missed case
when a message type is added later.

| Type | Definition |
|---|---|
| `SeatId` | A `String` label unique within one show, e.g. `"A12"`. Not globally unique. |
| `Money` | `BigDecimal`, scale exactly 2, non-negative. Serialized plain (never scientific notation). |
| `RejectionReason` | enum: `SEATS_ALREADY_HELD`, `SEATS_NOT_FOUND`, `SHOW_NOT_FOUND` |
| `PaymentFailureReason` | enum: `DECLINED`, `TIMEOUT`, `PROVIDER_ERROR` |
| `CancellationReason` | enum: `PAYMENT_FAILED`, `SEATS_UNAVAILABLE`, `RESERVATION_EXPIRED` |

Reasons are enums, not free text, so compensating logic branches on cause rather than parsing
prose (FR-009). `RESERVATION_EXPIRED` exists now because the step-4 fencing check will need it;
adding it later would mean versioning a contract that six services already consume.

## Messages

Envelope components are omitted below for brevity — all four appear on every record.

### OrderCreated
| Field | Type | Rule |
|---|---|---|
| `orderId` | `UUID` | Equals `sagaId`. Non-null. |
| `userId` | `UUID` | Non-null. |
| `showId` | `UUID` | The concert being ticketed. Non-null. |
| `seatIds` | `List<SeatId>` | Non-empty, no duplicates, defensively copied. |
| `amount` | `Money` | Non-negative, scale 2. |

### SeatsReserved
| Field | Type | Rule |
|---|---|---|
| `orderId` | `UUID` | Equals `sagaId`. |
| `seatIds` | `List<SeatId>` | Exactly the seats requested — reservation is all-or-nothing. |
| `reservationId` | `UUID` | Identity of the durable reservation. |
| `lockExpiresAt` | `Instant` | When the seat hold lapses. Non-null, strictly after `occurredAt`. |

`lockExpiresAt` is the fencing field. Seat holds expire on a timer, so a saga that stalls past
expiry may find its seats legitimately resold. Carrying the expiry lets the confirming service in
step 4 compare it against the current time and compensate instead of confirming a seat someone
else now holds (FR-008). Nothing consumes it in this step; it is present now so its addition does
not later require a version bump across services that already exist.

### SeatsRejected
| Field | Type | Rule |
|---|---|---|
| `orderId` | `UUID` | Equals `sagaId`. |
| `seatIds` | `List<SeatId>` | The seats that were requested. |
| `reason` | `RejectionReason` | Non-null. |

### PaymentSucceeded
| Field | Type | Rule |
|---|---|---|
| `orderId` | `UUID` | Equals `sagaId`. |
| `paymentId` | `UUID` | Non-null. |
| `amount` | `Money` | Matches the `OrderCreated` amount. |

### PaymentFailed
| Field | Type | Rule |
|---|---|---|
| `orderId` | `UUID` | Equals `sagaId`. |
| `reason` | `PaymentFailureReason` | Non-null. |

### OrderConfirmed
| Field | Type | Rule |
|---|---|---|
| `orderId` | `UUID` | Equals `sagaId`. |
| `seatIds` | `List<SeatId>` | The confirmed seats. |

### OrderCancelled
| Field | Type | Rule |
|---|---|---|
| `orderId` | `UUID` | Equals `sagaId`. |
| `reason` | `CancellationReason` | Non-null. |

## Referenced entities (not defined here)

**Show** — the concert being ticketed, identified by `showId`. Referenced by `OrderCreated`; its
storage is owned by a later step.

**Seat** — a sellable position within a show, labelled by `SeatId`. Owned by the inventory service
from step 3.

## State transitions

The order lifecycle is shown for context. The status enum itself is **not** in the contract
module — it is internal aggregate state owned by the order service, and only the transitions are
communicated as messages.

```text
                    OrderCreated
                         │
                    ┌────▼────┐
                    │ PENDING │
                    └────┬────┘
        SeatsRejected    │    SeatsReserved
            ┌────────────┴────────────┐
            │                         │
            │                  ┌──────▼──────┐
            │                  │  RESERVED   │
            │                  └──────┬──────┘
            │        PaymentFailed    │    PaymentSucceeded
            │            ┌────────────┴────────────┐
            │            │                         │
       ┌────▼────────────▼────┐            ┌───────▼────────┐
       │      CANCELLED       │            │   CONFIRMED    │
       │  emits OrderCancelled│            │emits OrderConfi│
       └──────────────────────┘            └────────────────┘
```

Both terminal states are absorbing. `RESERVED` is where the expiry risk lives: if the hold lapses
before payment resolves, the correct transition is to `CANCELLED` with reason
`RESERVATION_EXPIRED`, never to `CONFIRMED`.

## Channels

Fourteen channels, three partitions each, replication factor 1. Names are constants in
`Topics.java`, which lives in the contract module so a publisher cannot invent a name a consumer
does not know.

| Message | Channel | Dead-letter |
|---|---|---|
| OrderCreated | `order.created` | `order.created.DLT` |
| SeatsReserved | `seats.reserved` | `seats.reserved.DLT` |
| SeatsRejected | `seats.rejected` | `seats.rejected.DLT` |
| PaymentSucceeded | `payment.succeeded` | `payment.succeeded.DLT` |
| PaymentFailed | `payment.failed` | `payment.failed.DLT` |
| OrderConfirmed | `order.confirmed` | `order.confirmed.DLT` |
| OrderCancelled | `order.cancelled` | `order.cancelled.DLT` |

## Validation

Enforced in each record's compact canonical constructor, so an invalid message cannot be
constructed:

1. Every `UUID`, `Instant`, and enum field is non-null.
2. `sagaId` equals `orderId`.
3. `seatIds` is non-empty, duplicate-free, and defensively copied into an unmodifiable list.
4. `amount` is non-negative with scale exactly 2.
5. `schemaVersion` is at least 1.
6. On `SeatsReserved`, `lockExpiresAt` is strictly after `occurredAt`.

Validation lives in the constructor rather than a separate validator because a message that
cannot exist in an invalid state removes an entire class of consumer-side defensive checks.
