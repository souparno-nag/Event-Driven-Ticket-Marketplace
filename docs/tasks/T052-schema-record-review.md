# T052 — Reviewing seven schemas against seven records

**What this task did:** compared every JSON schema in `specs/001-event-contracts-foundation/contracts/`
against the Java record that implements it, field for field. **All seven matched on names, order,
required sets, and enum values. One real drift was found and fixed** — in test data, not in a
contract.

---

## Why this is a manual gate

The schemas are the **normative reference** for the wire format; the records in `common-events` are
the implementation of it. Nothing connects them at build time.

That is a deliberate choice, recorded in `contracts/README.md`: no schema registry, no code
generation. The brief specifies plain JSON, and a registry would be real infrastructure to run,
version, and keep available — for a system where the producer and every consumer are built from the
same repository.

The price is that the two can drift silently, and a review is the only thing standing in the way.
So this task exists to be *done*, not to be automated away — but "done by review" does not have to
mean "done by squinting".

---

## Doing the review mechanically

Reading fourteen files side by side is exactly the activity human attention is worst at. So the
comparison was scripted: parse each schema's `properties` and `required`, parse each record's
component list out of its source, and diff them.

```
OrderCreated       schema=9 record=9 required=9  OK
SeatsReserved      schema=8 record=8 required=8  OK
SeatsRejected      schema=7 record=7 required=7  OK
PaymentSucceeded   schema=7 record=7 required=7  OK
PaymentFailed      schema=6 record=6 required=6  OK
OrderConfirmed     schema=6 record=6 required=6  OK
OrderCancelled     schema=6 record=6 required=6  OK
```

Three things were checked, not one:

- **Names** — every schema property has a record component of the same name.
- **Order** — compared as ordered lists. JSON does not care about property order, but the two files
  are meant to be read side by side, and a divergence in order is an early symptom of one being
  edited without the other.
- **Required sets** — every field is required in the schema *and* non-optional in the record. No
  record component is missing from `required`, and nothing is required that the record does not
  have.

`additionalProperties: true` was confirmed on all seven, which is FR-007: a consumer must tolerate a
field a newer producer added. `false` would turn forward compatibility into a validation failure.

### Enum values

The three reason enums were compared value by value, in order, because these names **are the wire
format** — a renamed constant is a breaking change no compiler will report:

| Enum | Result |
|---|---|
| `RejectionReason` — SEATS_ALREADY_HELD, SEATS_NOT_FOUND, SHOW_NOT_FOUND | ✅ |
| `PaymentFailureReason` — DECLINED, TIMEOUT, PROVIDER_ERROR | ✅ |
| `CancellationReason` — PAYMENT_FAILED, SEATS_UNAVAILABLE, RESERVATION_EXPIRED | ✅ |

---

## The drift that was found

The schemas constrain `seatIds` entries with a pattern:

```json
"seatIds": {
  "items": { "type": "string", "pattern": "^[A-Z]+[0-9]+$" }
}
```

Letters then digits — `A12`, `B01`. Every seat label in the module's tests conforms… except the one
**I introduced in T048**, four tasks ago:

```java
List.of("A-" + sequence)     // "A-0" — a hyphen. Does not match.
```

Nothing failed. `OrderingGuaranteeIT` passed, the round-trip tests passed, the build was green —
because the record's validation checks that seat lists are non-empty and duplicate-free, not that
labels are well formed. The test was publishing messages that the published contract says are
invalid, and the only thing that would ever have noticed is this review.

Fixed to `"A" + sequence`, with a comment naming the schema it conforms to. The test still passes.

**Why bother, when nothing enforced it?** Because test data is copied. The next person writing a
producer looks at the nearest example of a seat label, and the invalid one propagates into code that
*does* get validated — at which point the failure appears somewhere far from here.

---

## A divergence that was left alone, deliberately

That fix raises the obvious follow-up: should `Validation` enforce the seat-label pattern, so a
malformed label cannot be constructed at all?

**No**, and the split is worth stating because it is a design decision rather than an oversight:

- **The record validates safety invariants** — the things where being wrong corrupts an order.
  Empty seat lists, duplicate seats, negative money, wrong decimal scale, a `sagaId` that disagrees
  with its `orderId`. Each of those has a failure mode measured in double-booked seats or incorrect
  charges.
- **The schema documents format** — what a well-formed message looks like on the wire.

`^[A-Z]+[0-9]+$` is a plausible convention, not a safety property. Real venues label seats `A-12`,
`12A`, `Row A Seat 12`. Baking the pattern into the contract module would reject legitimate labels
from a venue nobody has integrated yet, and the schema's own description calls `A12` an example
rather than a rule.

So the divergence is intentional: the schema is stricter than the record, and the record enforces
the subset where being wrong is expensive. This is recorded here so the next reviewer finds a
decision rather than rediscovering the gap.

---

## What it demonstrates

- **SC-007**: no identifier can be read as ambiguous between message identity and show identity,
  confirmed by review of every field name across all seven schemas and records. ✅
- **SC-010**: no message type carries a trace-correlation field. ✅ Confirmed while enumerating
  every property — the envelope is four fields, none of them a trace id (FR-008).
- **contracts/README.md's stated gate**: schema and record reviewed for alignment. ✅

---

## In one line

Seven schemas, seven records, compared by script rather than by eye — everything matched except a
seat label I had written four tasks earlier that quietly contradicted the contract it was meant to
demonstrate.
