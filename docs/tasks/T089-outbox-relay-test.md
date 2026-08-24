# T089 — Specifying eight guarantees of the relay, before it exists

**What this task did:** wrote the test for the eight core promises `contracts/outbox-relay.md` makes
about `pollAndPublish()` — against a class, `OutboxRelay`, that T097 has not yet created.

---

## Confirmed to fail, for the right reason

```text
OutboxRelayIT.java:[51,17] cannot find symbol
  symbol:   class OutboxRelay
```

Same pattern as every other test file that specifies a class ahead of its implementation: this one
will not compile until T097 builds `OutboxRelay`. Verified in isolation — with the four other new
files in this batch temporarily set aside, this is one of the two that still cannot compile alone,
confirming the missing `OutboxRelay` is genuinely this file's only problem.

## Rows with no order behind them, on purpose

Every test builds an `OutboxRecord` directly and saves it, without ever creating a real `Order`:

```java
outboxRepository.save(pendingRecord(aggregateId, Topics.ORDER_CREATED, orderCreatedPayload(aggregateId)));
```

The outbox table's `aggregate_id` deliberately carries no foreign key — a decision made back in
`data-model.md`, precisely so the outbox can be reasoned about as its own thing. These tests are
about the relay's mechanics, not about the mapping that produces a row (that is `OutboxWriter`'s job,
already specified and built in T074/T081), so there is no reason to pay for a real order just to have
something to point a row at.

## A real failure, not a simulated one

```java
private static final String UNPROVISIONED_CHANNEL = "no.such.channel";
```

Four of the eight guarantees are about failure — a row that cannot be sent, and what happens to it.
Rather than mocking `KafkaTemplate` to force an artificial exception, these tests point a row at a
channel `KafkaPostgresIT` never provisioned. With auto-creation disabled on the test broker, sending
there genuinely fails, the same way a real misconfiguration would. The failure this class observes is
the failure `OutboxRelay` will actually have to handle in production, not a stand-in for it.

## The eight tests, and what each is really checking

| Test | Guarantee | What would slip through without it |
|---|---|---|
| `publishesPendingRecord` | 1 | The message never leaves the database at all |
| `keysMessageBySagaId` | 2 | Kafka's per-order ordering has nothing to key on |
| `sendsStoredPayloadUnchanged` | 3 | Re-serializing reopens the `1E+2` money bug T070 exists to prevent |
| `marksPublishedOnlyAfterAck` | 4 | A row marked sent that the broker never actually received |
| `doesNotResendPublished` | 5 | A duplicate on every single run, not just an occasional one |
| `retainsFailedRecordForRetry` | 6 | A failed row silently vanishing from the retry queue |
| `parksAfterMaxAttempts` | 7 | A broken row retried forever, burning work and blocking its order |
| `oneFailureDoesNotStopTheBatch` | 8 | One bad row taking down every healthy order behind it |

**`doesNotResendPublished` was strengthened from its first draft.** The first version checked only
that `attempts` stayed at zero after re-running the relay against an already-published row. That is
a real signal, but an indirect one — it would not catch a relay that resent a message successfully
without recording it as a failure. The final version checks the guarantee directly: consume the
channel again after the repeated runs, and confirm the message with that row's key still appears
**exactly once**, no matter how many extra times the relay was asked to look at it.

**`marksPublishedOnlyAfterAck` cannot fully prove its own name alone.** On a fast local broker, a
relay that marks a row published *before* the acknowledgement returns would usually still succeed —
the send is quick enough that both things would appear true regardless of which one the code actually
waits for. The test's own comment says so plainly: it establishes that the successful case is
consistent (published and genuinely on the broker, together), while the real proof that the
acknowledgement is what's being awaited comes from `retainsFailedRecordForRetry` — a relay that
doesn't wait for the outcome has no way to notice a failure at all, and that test would catch it
directly.
