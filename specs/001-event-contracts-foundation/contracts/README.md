# Message Contracts

The external interface this feature exposes is the set of messages crossing service boundaries.
There is no HTTP API in this step — these schemas *are* the contract.

## Files

| File | Message |
|---|---|
| `envelope.schema.json` | The four fields common to every message, referenced by the rest |
| `order-created.schema.json` | `OrderCreated` |
| `seats-reserved.schema.json` | `SeatsReserved` |
| `seats-rejected.schema.json` | `SeatsRejected` |
| `payment-succeeded.schema.json` | `PaymentSucceeded` |
| `payment-failed.schema.json` | `PaymentFailed` |
| `order-confirmed.schema.json` | `OrderConfirmed` |
| `order-cancelled.schema.json` | `OrderCancelled` |

## Status of these schemas

They are the **normative reference** for the wire format, and the Java records in
`common-events` are the implementation of it. There is deliberately no runtime schema registry
and no code generation: the brief specifies plain JSON serialization, and introducing a registry
would add infrastructure this step does not need.

The consequence is that schema and record can drift. The round-trip test guards the record
against itself; keeping it aligned with these files is a review responsibility, called out in the
Definition of Done in [quickstart.md](../quickstart.md).

## Conventions

- **Field naming**: `camelCase`, matching the record component names exactly. Record accessors are
  `orderId()` rather than `getOrderId()`, which Jackson maps without configuration.
- **`additionalProperties: true`** on every message. This is deliberate and mirrors FR-007:
  a consumer must tolerate a field a newer producer added. Setting it `false` would make forward
  compatibility a validation failure.
- **Timestamps**: ISO-8601 UTC strings (`date-time`), never epoch numbers.
- **Money**: JSON `string`, not `number`. A JSON number is parsed as a double by many clients,
  which cannot represent decimal currency exactly — and the simulated payment decline rule
  depends on the last minor-unit digit being exact.
- **Unknown `schemaVersion`**: a consumer that does not recognise the value must route the message
  to the type's `.DLT` channel without processing it (FR-023). Schemas cannot express this; it is
  consumer behaviour, recorded here so the rule sits alongside the contract it governs.
