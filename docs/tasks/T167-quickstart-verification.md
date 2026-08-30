# T167 — Quickstart verification for User Story 2

**What this did:** attempted quickstart.md's S2 and S3 scenarios exactly as written, found the same
kind of gap T163 already found and named for S1 and S4, and recorded the direct, equivalent evidence
that already exists at the level this build step actually operates at.

---

## S2 and S3 cannot run exactly as written yet, for the identical reason S1 and S4 couldn't

Both scenarios open with "submit an order" via `curl` against `order-service`, then expect
`inventory-service` to react by consuming `order.created` from Kafka and, for S2/S3, to publish a
`seats.rejected` message in response. Checking the running environment directly confirms why that
can't happen yet:

```text
$ docker ps --format '{{.Names}}  {{.Status}}'
kafka     Up 20 hours (healthy)
redis     Up 20 hours (healthy)
postgres  Up 20 hours (healthy)
```

The infrastructure is up, but neither `order-service` nor `inventory-service` is itself running as a
process — and even if it were, `inventory-service` still has no `OrderCreatedListener`: the consumer
side of this service is User Story 3's work (T168 onward), not User Story 1 or 2's. T163's own
write-up already recorded this exact gap for S1 and S4; it applies here for the same reason and is not
a new discovery.

## The direct, equivalent evidence — proving the same guarantees at the level this build step actually built

S2 and S3 are both, underneath the `curl`-and-Kafka framing, asking two questions:

1. Does a contended request get refused *as a whole*, leaving even the seats that were free
   completely unheld? (S2, SC-002/SC-008)
2. Does each of the three refusal causes come from exactly the condition that names it, and no other?
   (S3, SC-008)

`ReservationRejectionIT` (T164) answers both directly against `ReservationService.decide(...)` — the
same method a real `order.created` message will call once User Story 3 wires up the consumer — with
nothing about the answer depending on whether the call arrived via Kafka or directly:

```text
Tests run: 3, Failures: 0, Errors: 0 -- ReservationRejectionIT
```

- `unknownShowIsRejectedAsShowNotFound` — S3's first row, `SHOW_NOT_FOUND`.
- `unknownSeatLabelInARealShowIsRejectedAsSeatsNotFound` — S3's second row, `SEATS_NOT_FOUND`.
- `seatAlreadyHeldByAnotherOrderIsRejectedAsSeatsAlreadyHeld` — S3's third row, `SEATS_ALREADY_HELD`,
  and simultaneously S2's own scenario: it requests one already-held seat *and* one genuinely free
  seat together, and asserts directly against `reservation_seats` that the free seat comes out exactly
  as unheld as it went in — the same claim S2's `redis-cli EXISTS` check makes, checked here against
  the durable store rather than the cache, which is the store PostgreSQL wins any disagreement with
  Redis (data-model.md).
- Every one of the three assertions above also confirms the outbox row's payload carries the FULL
  requested seat set, not merely the seat that caused the refusal — S2's own "carrying **both**
  labels" requirement, checked directly against the stored `jsonb` payload rather than a Kafka message
  that has no consumer to react to yet.

## What this checkpoint actually establishes

Every guarantee S2 and S3 exist to check is proven today, end to end against real PostgreSQL and real
Redis, at the one point in the system where the decision is actually made. The `curl`-and-Kafka framing
these two scenarios use becomes literally runnable the moment User Story 3 gives `inventory-service` a
consumer to react to `order.created` with — at which point re-running S2 and S3 as written would be
confirming the wiring, not the decision logic, which is already settled.
