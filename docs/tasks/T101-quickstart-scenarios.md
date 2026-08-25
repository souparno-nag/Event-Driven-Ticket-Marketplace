# T101: proving the relay against a running service, not just against tests

Every guarantee `contracts/outbox-relay.md` lists is already checked by an automated test. T101 is a
different kind of proof: run the actual, compiled application — the real `spring-boot:run` process,
against the real Docker containers — and watch three of `quickstart.md`'s scenarios happen with your
own eyes, the same way anyone reviewing this project for the first time would. A passing test suite
proves the code is correct; this proves the *running system* behaves the way the README will claim it
does.

All three scenarios were run against the project's own `core` Docker profile (Kafka, PostgreSQL,
Redis), started well before this task and already healthy.

## S2 — the message reaches the channel, keyed by the saga id (SC-004, SC-007, SC-008)

```
POST /api/orders  ->  202, orderId = 2251ffcf-9f2f-429c-a234-971d2bbcbd5d
```

Reading the `order.created` topic directly with `kafka-console-consumer` found exactly one message
for that order:

```
key:   2251ffcf-9f2f-429c-a234-971d2bbcbd5d
value: {"amount": 150.00, "sagaId": "2251ffcf-...", "orderId": "2251ffcf-...", ...}
```

The key matches the order id, and `amount` reads as a plain `150.00` — never the scientific notation
(`1.5E+2`) a naive JSON round-trip risks. **Confirmed.**

## S3 — atomicity survives a crash (SC-002, SC-005)

The service was started with `outbox.relay.poll-interval-ms=600000` (an hour), so nothing would poll
it away before the crash could be observed. An order was accepted (`orderId =
5f42460a-c410-448e-85e7-05d1c51470b8`), then the process was killed outright (`kill -9`, not a
graceful shutdown) before that hour was up.

```
status: PENDING, attempts: 0, published_at: (none)
```

— exactly the state a genuine mid-flight crash should leave behind: the order and its outbox row
committed together, nothing lost, but nothing sent either.

The service was then restarted with its **normal** configuration — no special flags, no manual
republish step, nothing telling it about the row left behind. Within about 4 seconds:

```
status: PUBLISHED, attempts: 0, published_at: 2026-08-25 11:37:12
```

The row was picked up and sent on the very first ordinary poll after restart, with `attempts` never
even needing to move past 0. This is the whole point of storing the trace in the database rather than
in the process's memory: a brand-new instance of the service has no idea it's "recovering" anything —
it just asks the database what's pending, the same question it would ask if it had been running
continuously. **Confirmed.**

*(An unrelated wrinkle along the way: the shared Kafka container the whole environment uses was
killed by the machine's own out-of-memory handling — a side effect of a long day of heavy testing on
this machine, not caused by this scenario. It happened to land during an earlier attempt at this exact
test, and starved that attempt's row of every one of its five retries before the broker came back,
parking it. That row is real and is one of the two counted in `outbox_records_parked` below; the
S3 attempt itself was simply redone cleanly once Kafka was healthy again, which is what these numbers
describe.)*

## S7 — a parked row stalls only its own order (SC-013)

A row aimed at a channel that will never exist was inserted directly:

```sql
INSERT INTO outbox (aggregate_id, event_type, payload)
VALUES ('33333333-3333-3333-3333-333333333333', 'no.such.channel', '{}'::jsonb);
```

A completely unrelated order was accepted at the same time
(`orderId = d987edf4-771a-4a90-bd32-c708fe765937`). Within seconds, that healthy order reached
`PUBLISHED` — normal, unaffected. The poisoned row, meanwhile, retried five times against a channel
that can never accept it:

```
status: PARKED, attempts: 5
last_error: TimeoutException: Topic no.such.channel not present in metadata after 5000 ms.
```

And the Prometheus endpoint reflects it:

```
outbox_records_parked 2.0
```

(Two, not one — the row from the S3 wrinkle above is a second, genuinely parked row still sitting in
the same shared database; both are real and both are correctly counted.)

The healthy order publishing promptly, right alongside a row that will never succeed, is FR-030's
guarantee made visible: a poisoned row only ever blocks *later rows for its own order* — never
anything belonging to someone else. **Confirmed.**

## Summary

| Scenario | Success criteria | Result |
|---|---|---|
| S2 | SC-004, SC-007, SC-008 | Confirmed |
| S3 | SC-002, SC-005 | Confirmed |
| S7 | SC-013 | Confirmed |
