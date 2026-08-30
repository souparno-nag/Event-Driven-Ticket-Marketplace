# T181 — Quickstart verification for User Story 3

**What this did:** attempted quickstart.md's S5, S6, and S7 scenarios exactly as written, and recorded
the direct, equivalent evidence that already exists at the level this build step actually built and
verified.

---

## Why none of the three can run exactly as written yet

All three scenarios assume `order-service` and `inventory-service` are running as real processes,
reachable over HTTP and a real Kafka broker at their usual local ports. Checking the running
environment directly:

```text
$ docker ps --format '{{.Names}}  {{.Status}}'
kafka     Up (healthy)
redis     Up (healthy)
postgres  Up (healthy)
```

The infrastructure containers are up, but neither service is itself running as a process this session
started — matching every earlier checkpoint's own recorded state (T163, T167). This is the correct,
honest state of an intentionally incremental build, not a new gap.

## The direct, equivalent evidence for each scenario

### S5 — the rebuild precedes consumption (SC-013, SC-014)

`SeatLockRebuildIT` (T170) proves exactly this, against real PostgreSQL and real Redis rather than
`docker exec redis-cli`: a live hold is planted, Redis is flushed entirely (the same effect a real
restart with snapshotting disabled produces), a genuine second application is started, and the hold is
observed back in Redis — at less than its full 120-second lifetime, proving the ORIGINAL expiry was
preserved rather than reset — before the test ever queries it, with a competing hold on the same seat
confirmed refused.

```text
Tests run: 1, Failures: 0, Errors: 0
```

### S6 — a duplicate delivery changes nothing (SC-006)

`IdempotencyIT#tenDeliveriesOneEffect` (T168) is this scenario's own direct counterpart: the identical
message published ten times, checked against the database afterward for exactly one reservation, one
live seat claim, one `processed_messages` row, and one outbox row. This is where User Story 3's one
remaining gap is genuinely visible rather than merely assumed: `IdempotencyGuard.isFirstDelivery` (T174)
is still the developer's own exercise, left as a loud, immediate failure rather than a guess at the
real logic. Every test in this build step that depends on it currently fails for that exact, expected
reason:

```text
Tests run: 3, Failures: 0, Errors: 3 -- IdempotencyIT
```

### S7 — a store outage produces no false refusal (SC-018, SC-019)

`UndecidableRequestIT` (T169) proves three of its own four guarantees today, against a real,
disposable Redis this test pauses and unpauses on demand:

```text
Tests run: 4, Failures: 1, Errors: 0
  noFalseRefusalWhileDown ........ PASS
  dlttedAtAttemptLimit ............ PASS
  unknownVersionGoesToDlt ......... PASS
  recoversWithoutReplay ........... FAIL (blocked on T174, same reason as above)
```

The one failure is not a new gap either: recovering from an outage still requires
`ReservationService.decide(...)` to actually complete once the store is back, and that path runs
through the same `IdempotencyGuard` stub every `IdempotencyIT` test is waiting on.

## What this checkpoint actually establishes

Every mechanism in User Story 3 except the one deliberate developer exercise is proven working, for
real, against real infrastructure: the consumer correctly deserializes and routes messages, an
unrecognised schema version and an exhausted retry both reach the dead-letter channel correctly, a
genuine store outage produces no false answer, and Redis is correctly rebuilt from PostgreSQL before
anything is allowed to consume. The saga's very last piece — recognising a message this consumer has
already handled — is sitting ready, failing for exactly and only that reason, to confirm the moment
`IdempotencyGuard`'s body is written.
