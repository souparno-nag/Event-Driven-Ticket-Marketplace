# T021 — `SeatsRejected`, the shortest path

**What this task did:** created `SeatsRejected.java` — the message saying the seats could not be
held, which ends the saga immediately.

---

## The shortest route through the saga

```
OrderCreated ──► inventory tries to hold ──► SeatsRejected ──► order CANCELLED
```

Three messages and it is over. No payment attempt, no compensation, nothing to undo — because the
hold is all-or-nothing, so a rejection means **no seat was ever taken**.

That is worth stating explicitly, because it is easy to assume every failure path needs an undo. The
cancellation that follows this message is *bookkeeping*: it moves the order to `CANCELLED` and tells
the customer why. Compare the payment-failure path, where seats really are held and really must be
released.

A useful habit when reading any saga: at each failure point, ask **what did the earlier steps
actually do?** That list is what compensation has to reverse. Here the list is empty.

---

## Why the full seat list comes back

```java
List<String> seatIds   // the seats that were requested
```

Not "the seats that were unavailable" — all of them.

This follows from all-or-nothing holds. The request was refused as a unit, so the message reports
the unit that was refused. Reporting the subset that was taken would suggest the rest were held,
which is exactly what did not happen.

There is a second reason, from T008: naming *which* seat lost would let a client retry seat by seat
and pick a row clean one at a time, turning a clean atomic operation into a race. Withholding that
detail is deliberate.

---

## `reason` as an enum, and its limits

```java
RejectionReason reason   // SEATS_ALREADY_HELD | SEATS_NOT_FOUND | SHOW_NOT_FOUND
```

This is **FR-009** in practice: rejection messages carry a machine-comparable cause, so a consumer
branches on it rather than parsing prose. The full argument is in T008's document — the short version
is that `SEATS_ALREADY_HELD` might succeed on retry while `SEATS_NOT_FOUND` never will, and a
consumer that cannot tell them apart either retries forever or gives up too early.

It is required, not nullable. A rejection with no stated cause is the one message nobody can act on
in the moment or explain a month later.

Worth remembering the constraint that comes with the choice, from T016: **adding an enum value is a
breaking wire change.** A consumer on an older build calls `valueOf` on a name it does not have and
throws. So a fourth rejection cause is not a local edit; it needs a `schemaVersion` bump and the
dead-letter path. That asymmetry — new *fields* are free, new *enum values* are not — is why T010
declared `RESERVATION_EXPIRED` before anything produced it.

---

## What this record does not do

It carries no suggestion of what to do next: no `retryable` flag, no `retryAfter`. The consumer
decides, from the reason, using its own policy.

That separation is the difference between an event and a command. `retryable: true` would be the
producer telling the consumer how to behave — and inventory-service does not know whether
order-service wants to retry, wait, or give up. It knows what happened. That is all a fact should
claim.

---

## Try it yourself

```bash
./mvnw -pl common-events compile
```

**Expect**: `BUILD SUCCESS`, 9 source files. Four records left.

To see why the enum matters on the wire, note that Jackson serializes it as its plain name:

```json
{ "reason": "SEATS_ALREADY_HELD" }
```

which is the same text listed in `specs/001-event-contracts-foundation/contracts/seats-rejected.schema.json`.
Those two must agree, and T052 later checks every schema field-for-field against its record by hand
— there is no registry enforcing it.

---

## What comes next

**T022** — `PaymentSucceeded`, the happy path's turning point, and the record where carrying the
amount a second time is a deliberate choice rather than redundancy.
