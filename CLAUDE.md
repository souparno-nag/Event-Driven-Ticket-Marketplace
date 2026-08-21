# PROJECT: Event-Driven Ticket Marketplace

Build a multi-module Maven project demonstrating a choreography-based Saga over Kafka.
Optimize for: working end-to-end demo, clear code I can explain in an interview.
Do NOT optimize for: production hardening, exhaustive tests, auth edge cases.

## STACK

Java 21, Spring Boot 3.3.x, Spring Cloud 2023.x, Apache Kafka (KRaft, no Zookeeper),
Redis 7, PostgreSQL 16, Elasticsearch 8, Resilience4j, Spring Cloud Gateway,
Eureka, Micrometer Tracing + Zipkin, Prometheus, Docker Compose, Helm, Minikube.

## MODULES

common-events/     — shared event DTOs, no Spring deps
api-gateway/       — Spring Cloud Gateway, JWT validation, routing
auth-service/      — issues JWTs (HS256, hardcoded users, in-memory). Keep it minimal.
order-service/     — Postgres. Owns Order aggregate + transactional outbox.
inventory-service/ — Redis (seat locks) + Postgres (durable reservations).
payment-service/   — SIMULATED. Fails if amount ends in 7, else succeeds after 500ms.
projection-service/— Kafka consumer → Elasticsearch read model.

Each service: own Dockerfile, own application.yml, registers with Eureka,
exports /actuator/prometheus, traces to Zipkin.

## EVENT CONTRACTS (define in common-events FIRST, everything depends on these)

Every event has: eventId (UUID), sagaId (= orderId), occurredAt (Instant), version (int).
JSON serialization, topic-per-event-type.

OrderCreated      { orderId, userId, eventId_ticketed, seatIds[], amount }
SeatsReserved     { orderId, seatIds[], reservationId }
SeatsRejected     { orderId, seatIds[], reason }
PaymentSucceeded  { orderId, paymentId, amount }
PaymentFailed     { orderId, reason }
OrderConfirmed    { orderId, seatIds[] }
OrderCancelled    { orderId, reason }

## SAGA FLOW (implement exactly this)

HAPPY:  POST /orders → Order(PENDING) + outbox row in ONE tx
        → OrderCreated → inventory locks seats → SeatsReserved
        → payment charges → PaymentSucceeded
        → order → CONFIRMED (emits OrderConfirmed)
        → inventory commits reservation to Postgres

COMPENSATE (payment fails): PaymentFailed → order → CANCELLED
        → OrderCancelled → inventory releases Redis locks + deletes reservation

COMPENSATE (no seats): SeatsRejected → order → CANCELLED immediately

## CRITICAL IMPLEMENTATION REQUIREMENTS

1. TRANSACTIONAL OUTBOX (order-service)
   Table `outbox(id, aggregate_id, event_type, payload jsonb, created_at, published_at)`.
   Order row + outbox row written in the SAME @Transactional method.
   A @Scheduled(fixedDelay=500) poller selects WHERE published_at IS NULL,
   publishes to Kafka, marks published. Use SELECT ... FOR UPDATE SKIP LOCKED.
   >>> TODO(me): scaffold the table, entity, and poller class signature. Leave the
   >>> poll+publish method body as `// TODO` with a comment explaining the contract.

2. SEAT LOCKING (inventory-service)
   Redis key format: `seat:{eventId}:{seatId}` → value = orderId, TTL 120s.
   Must be an atomic Lua script via `DefaultRedisScript<Long>`: check ALL keys free,
   then set ALL keys, else return 0. All-or-nothing.
   >>> TODO(me): create `lock_seats.lua` and `release_seats.lua` in resources/scripts/
   >>> as empty files with a header comment describing the contract. Wire up the
   >>> DefaultRedisScript beans and the calling service method, but leave the .lua
   >>> bodies for me to write.

3. IDEMPOTENCY (every consumer)
   Table `processed_events(event_id UUID PRIMARY KEY, consumer_name, processed_at)`.
   Insert in the same tx as the state change; catch DataIntegrityViolationException → skip.
   >>> TODO(me): generate the table + entity, leave the guard method body as a stub.

4. OPTIMISTIC CONCURRENCY
   @Version column on Reservation and Order entities. Retry once on
   OptimisticLockingFailureException.

5. RESILIENCE4J (on inventory→payment path and gateway→services)
   - CircuitBreaker: 50% failure rate, 10-call sliding window, 10s open state
   - Retry: 3 attempts, exponential backoff — ONLY on idempotent ops
   - TimeLimiter: 3s
   - Fallback methods that emit the compensating event rather than throwing

6. CQRS READ MODEL (projection-service)
   Consumes all order/inventory events → upserts an `event_availability` doc in
   Elasticsearch: { eventId, totalSeats, availableSeats, seatStatusMap }.
   Expose GET /availability/{eventId} reading ONLY from Elasticsearch.

## APIs

POST /api/orders              {userId, eventId, seatIds[], amount} → 202 + orderId
GET  /api/orders/{id}         → current saga state
GET  /api/availability/{id}   → read model (projection-service)
POST /api/auth/login          {username,password} → JWT
All /api/** except /auth require Bearer JWT, validated at the gateway.

## INFRA

docker-compose.yml with: kafka(KRaft), redis, postgres, elasticsearch, zipkin,
prometheus, eureka. Include healthchecks and depends_on conditions.
Auto-create topics on startup via a @Bean NewTopic per event type.
Flyway migrations for all Postgres schemas.

helm/ticket-marketplace/ — one chart, one values.yaml driving all services
(image, replicas, env, resources). Deployment + Service per microservice.
Target Minikube. Skip ingress, HPA, and secrets management.

## LOAD TEST (required deliverable)

k6 script at loadtest/booking.js: 1000 virtual users booking from a pool of 10 seats
simultaneously. Must assert exactly 10 succeed, 990 rejected, zero double-bookings.
Include a make target to run it.

## BUILD ORDER — do these as separate, verifiable steps and pause after each

1. common-events + docker-compose + parent pom. Verify all containers healthy.
2. order-service with outbox. Verify: POST /orders writes both rows, event lands in Kafka.
3. inventory-service. Verify: happy path locks seats, SeatsReserved published.
4. payment-service + saga completion. Verify: order → CONFIRMED end to end.
5. Compensation path. Verify: amount ending in 7 → CANCELLED + locks released.
6. projection-service + Elasticsearch.
7. gateway + auth.
8. Resilience4j.
9. k6 load test. 10. Helm + Minikube. 11. README.

## CONSTRAINTS

- No Lombok — write real getters, I need to read this code cold in an interview.
- Every non-obvious line gets a WHY comment, not a WHAT comment.
- No abstract base classes or generic frameworks. Flat and obvious beats clever.
- If a design decision has a real tradeoff, add a `// TRADEOFF:` comment naming
  the alternative and why it was rejected.
- Do not write unit tests for getters/setters. Do write one integration test per
  saga path (happy + both compensations) using Testcontainers.

## README must contain

ASCII saga diagram (happy + both failure paths), why choreography over orchestration,
why outbox, why Lua vs SETNX loop, idempotency strategy, read-model consistency window,
k6 output screenshot placeholder, local run instructions.
