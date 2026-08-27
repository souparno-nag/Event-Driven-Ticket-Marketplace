# Quickstart: Seat Holds & the Inventory Authority

**Feature**: `003-inventory-seat-locks` | **Date**: 2026-08-27

How to scaffold the module, run it, and prove each success criterion. Verified environment: OpenJDK
21.0.11, Maven wrapper 3.9.16, Docker 29.7.2, Compose v2.39.1.

---

## 0. Scaffold the module — you run this

Per Constitution Principle V, fetching a generated project is yours to do, not mine. It is also how
every module here has been created so far.

Open **https://start.spring.io** and set:

| Field | Value |
|---|---|
| Project | Maven |
| Language | Java |
| Spring Boot | **3.3.x** (must match the root pom; not 4.x) |
| Group | `com.marketplace` |
| Artifact | `inventory-service` |
| Package name | `com.marketplace.inventory` |
| Packaging | Jar |
| Java | 21 |

Dependencies: **Spring Web**, **Spring Data JPA**, **PostgreSQL Driver**, **Spring Data Redis**,
**Spring for Apache Kafka**, **Flyway Migration**, **Spring Boot Actuator**, **Validation**.

Then:

```bash
cd ~/Projects/Event-Driven-Ticket-Marketplace
unzip ~/Downloads/inventory-service.zip -d .
rm -rf inventory-service/.mvn inventory-service/mvnw inventory-service/mvnw.cmd inventory-service/.gitignore
```

The wrapper and gitignore are deleted because the repository root already provides both; a second
wrapper in a child module is a second Maven version waiting to disagree with the first.

Tell me when that is in place and I will make the post-generation edits — repointing the parent at
`ticket-marketplace`, rewriting the Boot 4 starter names to their 3.3 equivalents, adding
`common-events`, tracing and Prometheus, and registering the module in the root pom.

---

## 1. Start the environment

```bash
make up          # `core` profile: Kafka, PostgreSQL, Redis
make health      # one line per component
```

**For the tracing check (SC-015) you need Zipkin**, which lives in the `obs` profile:

```bash
sed -i 's/^COMPOSE_PROFILES=.*/COMPOSE_PROFILES=core,obs/' infra/.env
make up
```

---

## 2. Build and run

```bash
./mvnw -q -pl inventory-service -am verify     # unit + integration tests
./mvnw -pl inventory-service spring-boot:run   # port 8082
```

order-service must also be running for the end-to-end scenarios:

```bash
./mvnw -pl order-service spring-boot:run       # port 8081, separate terminal
```

The seeded seating plan is applied by `V1__create_seating_plan.sql`. Note the ten-seat show's id — the
scenarios below refer to it as `$SHOW`.

```bash
export SHOW=$(psql -h localhost -U marketplace -d marketplace -At \
  -c "SELECT show_id FROM inventory.shows WHERE name = 'Load Test Hall'")
```

---

## 3. Validation scenarios

### S1 — A booking is held, and the outcome is announced (SC-009)

```bash
curl -s -XPOST localhost:8081/api/orders -H 'content-type: application/json' \
  -d "{\"userId\":\"$(uuidgen)\",\"showId\":\"$SHOW\",\"seatIds\":[\"A1\",\"A2\"],\"amount\":\"90.00\"}"
```

Expect, within about two seconds:

```bash
# the durable reservation
psql … -c "SELECT status, lock_expires_at FROM inventory.reservations ORDER BY created_at DESC LIMIT 1"
#  HELD | <now + 120s>

# the seats claimed
psql … -c "SELECT seat_label, released_at FROM inventory.reservation_seats ORDER BY seat_label"
#  A1 | NULL      A2 | NULL

# the holds in Redis, with a TTL counting down
docker exec redis redis-cli --scan --pattern "seat:$SHOW:*"
docker exec redis redis-cli TTL "seat:$SHOW:A1"      # ~120, falling

# the announcement on the wire
docker exec kafka kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic seats.reserved --from-beginning --max-messages 1 --property print.key=true
```

The printed key must equal the order id, and `lockExpiresAt` must be strictly later than `occurredAt`.

### S2 — A contended request is refused whole, holding nothing (SC-002, SC-008)

Submit a second order for `A2` and `A3` while the first hold is live. Expect a `seats.rejected` message
with `reason: SEATS_ALREADY_HELD` carrying **both** labels, and:

```bash
docker exec redis redis-cli EXISTS "seat:$SHOW:A3"   # 0 — the free seat stayed free
```

A3 remaining free is the assertion that matters. A design that takes what it can and rolls back would
briefly show `1` here, and another contender could have been refused because of it.

### S3 — Each refusal cause comes from its own condition (SC-008)

| Request | Expected `reason` |
|---|---|
| a `showId` that is not in `shows` | `SHOW_NOT_FOUND` |
| a real show, seat label `Z99` | `SEATS_NOT_FOUND` |
| a real show, seats currently held | `SEATS_ALREADY_HELD` |

### S4 — Holds lapse on their own (SC-005)

Submit an order, then wait. After 120 s:

```bash
docker exec redis redis-cli EXISTS "seat:$SHOW:A1"   # 0
```

The reservation is still `HELD` in PostgreSQL at this point — nothing has contended for the seat yet.
Rebook `A1` and it succeeds on the first attempt, retiring the lapsed reservation in the same
transaction (SC-016):

```bash
psql … -c "SELECT status FROM inventory.reservations ORDER BY created_at"
#  EXPIRED   (the first)
#  HELD      (the rebooking)
```

Run this with the sweeper disabled (`inventory.sweeper.enabled=false`) to prove correctness does not
depend on it.

### S5 — The rebuild precedes consumption (SC-013, SC-014)

```bash
# with a live hold in place:
docker exec redis redis-cli FLUSHALL
# restart inventory-service, then immediately:
docker exec redis redis-cli TTL "seat:$SHOW:A1"
```

Expect a positive TTL **smaller than 120** — the hold was restored to its original expiry, not given a
fresh lifetime. Then submit a competing order for `A1` and confirm it is refused. A service that starts
consuming before the rebuild finishes grants it instead, and looks perfectly healthy while doing so.

### S6 — A duplicate delivery changes nothing (SC-006)

Republish the same `order.created` message ten times with an identical `messageId`:

```bash
psql … -c "SELECT count(*) FROM inventory.reservations WHERE order_id = '<id>'"        # 1
psql … -c "SELECT count(*) FROM inventory.processed_messages WHERE message_id = '<m>'" # 1
```

Exactly one outcome must appear on `seats.reserved`.

### S7 — A store outage produces no false refusal (SC-018, SC-019)

```bash
docker stop redis
# submit an order via order-service
docker exec kafka kafka-console-consumer --bootstrap-server localhost:9092 \
  --topic seats.rejected --from-beginning --timeout-ms 8000
#  → no message. The request is retried, not answered.

docker start redis
#  → within the backoff window the order is decided normally, with no manual step
```

Then confirm the bounded end: a message that can never be decided reaches `order.created.DLT` at the
attempt limit, and `inventory_messages_deadlettered_total` increments.

### S8 — One connected trace (SC-015)

With `COMPOSE_PROFILES=core,obs`, submit one order and open http://localhost:9411. Expect a **single**
trace spanning order-service's acceptance, its outbox publish, this service's decision, and its own
outbox publish — not four unrelated traces.

### S9 — Contention at scale (SC-001, SC-003)

This one is the integration suite rather than a shell command, because 1,000 genuinely simultaneous
callers cannot be produced with `curl`:

```bash
./mvnw -q -pl inventory-service test -Dtest='Reservation*IT'
```

`ReservationContentionIT` asserts exactly 10 of 1,000 succeed against a 10-seat pool with zero seats in
two holds, repeated 20 times. `ReservationDisjointIT` asserts 500 concurrent requests for disjoint
seats all succeed — the check that catches a lock which serialises everything and looks correct because
nothing double-books.

### S10 — The database refuses a double-booking on its own (SC-017)

```bash
./mvnw -q -pl inventory-service test -Dtest=LiveSeatConstraintIT
```

Inserts two live `reservation_seats` rows for one seat with Redis bypassed entirely, and expects the
unique index to reject the second. This is the guarantee that holds if the Lua script is ever wrong.

---

## 4. Reset

```bash
make down        # removes volumes — a clean slate
make up
```

---

## Definition of Done

- [ ] Module scaffolded, registered in the root pom, `./mvnw verify` green from a clean checkout
- [ ] `lock_seats.lua` and `release_seats.lua` implemented; `SeatLockScriptIT` passes
- [ ] S1–S10 all pass as written
- [ ] SC-001 repeatable across 20 consecutive runs with no deviation
- [ ] No refusal is ever produced for a failure that was not about the seats
- [ ] Every non-obvious line carries a WHY comment; every real tradeoff carries a `TRADEOFF:` comment
- [ ] `docs/tasks/` carries a beginner-level explanation for each task in this step
