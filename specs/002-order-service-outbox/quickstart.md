# Quickstart: Order Acceptance & the Transactional Outbox

**Feature**: `002-order-service-outbox` | **Date**: 2026-08-23

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
| Artifact | `order-service` |
| Package name | `com.marketplace.orders` |
| Packaging | Jar |
| Java | 21 |

Dependencies: **Spring Web**, **Spring Data JPA**, **PostgreSQL Driver**, **Spring for Apache
Kafka**, **Flyway Migration**, **Spring Boot Actuator**, **Validation**.

Then:

```bash
cd ~/Projects/Event-Driven-Ticket-Marketplace
unzip ~/Downloads/order-service.zip -d .
rm -rf order-service/.mvn order-service/mvnw order-service/mvnw.cmd order-service/.gitignore
```

The wrapper and gitignore are deleted because the repository root already provides both; a second
wrapper in a child module is a second Maven version waiting to disagree with the first.

Tell me when that is in place and I will make the post-generation edits — repointing the parent at
`ticket-marketplace`, adding `common-events`, adding tracing and Prometheus dependencies Initializr
does not offer together, and registering the module in the root pom.

---

## 1. Start the environment

```bash
make up          # starts the `core` profile: Kafka, PostgreSQL, Redis
make health      # one line per component
```

**For the tracing check (SC-012) you need Zipkin**, which lives in the `obs` profile:

```bash
sed -i 's/^COMPOSE_PROFILES=.*/COMPOSE_PROFILES=core,obs/' infra/.env
make up
```

Five components, roughly 1.6 GiB. Everything except SC-012 runs fine on `core` alone at ~1.1 GiB.

---

## 2. Build and run

```bash
./mvnw -q verify                                   # all modules, unit + integration tests
./mvnw -pl order-service spring-boot:run           # http://localhost:8081
```

The relay tests fail until the stubbed method is implemented. That is the intended state — see
[`contracts/outbox-relay.md`](./contracts/outbox-relay.md).

---

## 3. Validation scenarios

### S1 — A booking request is accepted, and both rows are written (SC-001, SC-010)

```bash
ORDER=$(curl -s -X POST http://localhost:8081/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"userId":"11111111-1111-1111-1111-111111111111",
       "showId":"22222222-2222-2222-2222-222222222222",
       "seatIds":["A1","A2"],
       "amount":"150.00"}' | jq -r .orderId)
echo "$ORDER"
```

Expect `202`, a `Location` header, and `status: PENDING`. Then confirm both rows exist:

```bash
docker exec -i postgres psql -U marketplace -d marketplace -c \
  "SELECT o.id, o.status, ob.event_type, ob.status AS outbox_status
     FROM orders o JOIN outbox ob ON ob.aggregate_id = o.id
    WHERE o.id = '$ORDER';"
```

Expect exactly one row: `PENDING`, `order.created`, and an outbox status of `PENDING` before the
relay runs or `PUBLISHED` after.

Read it back — every field unchanged:

```bash
curl -s http://localhost:8081/api/orders/$ORDER | jq
```

### S2 — The message reaches the channel, keyed by the saga id (SC-004, SC-007, SC-008)

```bash
docker exec -i kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 --topic order.created \
  --from-beginning --property print.key=true --timeout-ms 8000
```

Expect the key to equal the order id and the value to be the `OrderCreated` JSON, with `amount` as
`150.00` — never `1.5E+2`.

### S3 — Atomicity survives a crash (SC-002, SC-005)

```bash
# Kill the service between commit and publish: stop it while the relay is paused.
./mvnw -pl order-service spring-boot:run -Dspring-boot.run.arguments=--outbox.relay.poll-interval-ms=600000 &
# ... POST an order as in S1, then:
kill %1
docker exec -i postgres psql -U marketplace -d marketplace -c \
  "SELECT status, attempts FROM outbox WHERE aggregate_id = '$ORDER';"   # PENDING, 0
./mvnw -pl order-service spring-boot:run &                                # restart normally
# within ~10s the row becomes PUBLISHED with no manual step
```

### S4 — Invalid requests are refused and nothing is recorded (SC-009)

```bash
for BAD in '{"userId":"11111111-1111-1111-1111-111111111111","showId":"22222222-2222-2222-2222-222222222222","seatIds":[],"amount":"10.00"}' \
           '{"userId":"11111111-1111-1111-1111-111111111111","showId":"22222222-2222-2222-2222-222222222222","seatIds":["A1","A1"],"amount":"10.00"}' \
           '{"userId":"11111111-1111-1111-1111-111111111111","showId":"22222222-2222-2222-2222-222222222222","seatIds":["A1"],"amount":"10.5"}'; do
  curl -s -o /dev/null -w '%{http_code} ' -X POST http://localhost:8081/api/orders \
       -H 'Content-Type: application/json' -d "$BAD"
done; echo
```

Expect `400 400 400`, each naming the offending field, and the `orders` row count unchanged.

### S5 — Overload is refused fast and readably (SC-016)

```bash
seq 1 500 | xargs -P 200 -I{} curl -s -o /dev/null -w '%{http_code}\n' \
  -X POST http://localhost:8081/api/orders -H 'Content-Type: application/json' \
  -d '{"userId":"11111111-1111-1111-1111-111111111111",
       "showId":"22222222-2222-2222-2222-222222222222",
       "seatIds":["A1"],"amount":"10.00"}' | sort | uniq -c
```

Expect only `202` and `503`. A `500` is a defect; a `400` here would mean overload is being reported
as a bad request, which FR-036 forbids.

### S6 — One connected trace (SC-012)

Requires the `obs` profile from step 1. POST an order, wait for the relay, then open
**http://localhost:9411** and search by service `order-service`. Expect **one** trace containing both
the `POST /api/orders` span and the later publish span — not two unrelated traces.

### S7 — A parked row stalls its own order and nothing else (SC-013)

```bash
docker exec -i postgres psql -U marketplace -d marketplace -c \
  "INSERT INTO outbox (aggregate_id, event_type, payload)
   VALUES ('33333333-3333-3333-3333-333333333333', 'no.such.channel', '{}'::jsonb);"
```

Within five poll cycles that row becomes `PARKED` with `last_error` populated, while orders created
in S1 keep publishing normally. Confirm it is visible as a metric:

```bash
curl -s http://localhost:8081/actuator/prometheus | grep -E 'outbox_records_parked|outbox_oldest_pending'
```

### S8 — Throughput and latency (SC-003, SC-014, SC-015)

The k6 harness arrives in build step 9. Until then, an approximate check:

```bash
ab -n 5000 -c 200 -p order.json -T application/json http://localhost:8081/api/orders
```

Expect a 95th percentile under 300 ms and a sustained rate above 200/sec, and confirm the backlog
drains to empty within 30 seconds of the run ending:

```bash
watch -n1 "docker exec -i postgres psql -U marketplace -d marketplace -tc \
  \"SELECT count(*) FROM outbox WHERE status='PENDING';\""
```

`ab` is not installed by default — `sudo apt install apache2-utils` if you want this check now, or
simply wait for step 9's k6 script, which measures the same thing properly.

---

## 4. Reset

```bash
make down    # stops everything and deletes its volumes
make up
```

`make down` removes the PostgreSQL volume, so Flyway replays from `V1` on the next start. That is the
clean-checkout path from SC-011 and is worth exercising at least once.
