# Saga Build Path — steps 2 to 11

Build step 1 is complete and verified: seven sealed message contracts, the multi-module build, six
containers with health checks and profiles, fourteen Kafka channels, and a 100-order ordering
guarantee proven against a real broker. 55 of 56 tasks, 39 tests green.

This document is what the remaining ten steps contain — scope, prerequisites, what to write by hand,
what to verify, and the traps already identified in each. Build steps come from `CLAUDE.md`; each one
is a separate feature with its own `specs/` directory, not a phase of the existing one.

---

## The cycle, repeated per step

The constitution requires the full workflow before code is written, so no step starts with
implementation.

```bash
# 1 — describe the step's scope in one sentence
/speckit-specify  "Build step N: <scope>"

# 2 — optional, when the scope has genuine ambiguity
/speckit-clarify

# 3 — technical approach, checked against the constitution
/speckit-plan

# 4 — the task breakdown
/speckit-tasks

# 5 — one task at a time, one commit each
/speckit-implement  "Implement T0NN"
```

Three standing arrangements:

- **You scaffold the Spring modules** from start.spring.io. That is a tool action and Principle V
  keeps it yours — generate it, drop it in the repo, and it gets wired into the build.
- **You run anything that changes the machine** — `make up`, installations, provisioning. Maven
  commands are run for you and the results reported.
- **Each task ends with a commit and a `docs/tasks/` explainer** written for someone new to the
  technology. That has held for 56 tasks and is what makes the history readable.

---

## Three decisions before step 2

None of these blocks you today, but each gets more expensive once code exists.

### One spec per build step, or bundle the saga?

Steps 2 through 5 build one saga between them, so specifying them as a single feature is tempting.
Bundling gives a spec you can reason about whole; splitting keeps each step independently
verifiable, which is what the brief's "pause after each" asks for and what made step 1 tractable.

**Recommendation: one spec per step.**

### When do services register with Eureka?

The registry does not exist until step 7, so services built in steps 2 through 6 have nothing to
register with. Either add the client to each service as it is built — dormant and misconfigured for
five steps — or add it to all of them at step 7 in one pass.

**Recommendation: retroactively at step 7.**

### Does `make up` build jars first?

Every component today is a pulled image. From step 7 the Eureka server is a service this project
compiles, so its Compose entry needs a `build:` context and a jar that exists. Either `make up` gains
a build dependency, or starting the environment becomes a two-command sequence.

**Decide when you write step 7.**

---

## Step 2 — order-service and the transactional outbox

**Scaffold.** start.spring.io — Web, JPA, PostgreSQL Driver, Spring for Apache Kafka, Flyway,
Actuator, Validation. Java 21, Maven. Then swap its parent to `ticket-marketplace`, add the
`common-events` dependency, and add one `<module>` line to the root pom.

**Build.**

- Flyway migration for `orders` and `outbox(id, aggregate_id, event_type, payload jsonb, created_at, published_at)`
- `POST /api/orders` → 202 with the order id
- Order row and outbox row written in **one** `@Transactional` method
- `@Scheduled(fixedDelay=500)` poller using `SELECT … FOR UPDATE SKIP LOCKED`
- `@Version` on the Order entity

**You write.** The poller's poll-and-publish body. The brief asks for the table, entity, and class
signature to be scaffolded, with the method left as a stub carrying a comment that describes the
contract.

**Verify.** One `POST` writes both rows in the same transaction, and the event appears in
`order.created`.

**Traps.**

- **Configure Jackson in this service.** The contract module ships annotations only, not databind, so
  `write-bigdecimal-as-plain` has to be set here. Without it a money amount can serialize as `1E+2`,
  which the schema pattern rejects.
- **No Eureka client yet.** There is no registry until step 7.

---

## Step 3 — inventory-service and the seat locks

**Scaffold.** Web, JPA, PostgreSQL Driver, Data Redis, Kafka, Flyway, Actuator.

**Build.**

- Redis key `seat:{showId}:{seatId}` → orderId, TTL 120s
- `DefaultRedisScript<Long>` beans and the calling service method
- Consumes `OrderCreated` → emits `SeatsReserved` or `SeatsRejected`
- `processed_events(event_id UUID PRIMARY KEY, consumer_name, processed_at)`
- `@Version` on Reservation, retry once on optimistic lock failure

**You write.** `lock_seats.lua` and `release_seats.lua`, created as empty files with a header comment
describing the all-or-nothing contract. Also the idempotency guard body.

**Traps.**

- **The key uses `showId`, not `eventId`.** The contracts renamed that field precisely because it was
  ambiguous with message identity; the brief's original key format predates the rename.
- **This is where the constitution bites hardest.** Concurrent reservation is named explicitly as
  requiring tests that exercise concurrent execution. A happy-path test is not sufficient.

---

## Step 4 — payment-service and saga completion

**Scaffold.** Web, Kafka, Actuator. No database — payment is simulated.

**Build.**

- Consumes `SeatsReserved`; fails when the amount ends in 7, otherwise succeeds after 500 ms
- order-service consumes `PaymentSucceeded` → CONFIRMED, emits `OrderConfirmed`
- inventory-service consumes `OrderConfirmed` → commits the reservation to Postgres

**Verify.** An order reaches CONFIRMED end to end, with every hop visible in Kafka.

**Trap.** *"Ends in 7" means the minor unit.* Amounts carry a scale of exactly two, so the failing
case is `49.97`, not `7.00`. Decide it once and write it into the spec.

---

## Step 5 — both compensation paths

**Build.**

- `PaymentFailed` → order CANCELLED → `OrderCancelled` → inventory releases the Redis locks and
  deletes the reservation
- `SeatsRejected` → order CANCELLED immediately, no payment attempted

**Verify.** An amount ending in 7 reaches CANCELLED and the seat locks are released — confirmed in
Redis, not inferred from a log line.

**The test bar for this step.** One integration test per saga path — happy, payment-failed, and
seats-rejected — using Testcontainers. This is the step where the saga first has three observable
outcomes, and the last comfortable moment to write those tests.

---

## Step 6 — projection-service and the read model

**Scaffold.** Web, Kafka, Data Elasticsearch, Actuator. Switch the environment to
`COMPOSE_PROFILES=full` from here on.

**Build.**

- Consumes all order and inventory events → upserts an `event_availability` document
- `GET /api/availability/{id}` reads **only** from Elasticsearch

**The trap that matters most in this roadmap.**

This is the first consumer that reads several channels at once, and Kafka promises it nothing about
their relative order. Ordering is guaranteed within a topic-partition — `OrderingGuaranteeIT` proved
that for one channel. Across channels there is no guarantee at all, so this service can legitimately
observe `OrderConfirmed` before `SeatsReserved` for the same order whenever it is behind on one of
them.

Design the projection to be order-insensitive from the start: last-write-wins on `occurredAt`, or a
state machine that tolerates arriving out of sequence. A projection that assumes arrival order will
look correct in testing and corrupt itself under load.

---

## Step 7 — gateway, auth, and the service registry

**Do this first.** The root pom has **no Spring Cloud BOM**. Add `spring-cloud-dependencies` 2023.0.x
to `dependencyManagement` before anything else in this step — Eureka, the gateway, and Resilience4j
all need it. The Boot version is pinned at 3.3.13 specifically so this train pairs cleanly.

**Build.**

- `eureka-server` module — `@EnableEurekaServer`, its own Dockerfile, a Compose entry on port 8761
  with a `build:` context, roughly 384 MiB
- Eureka client added to every service built so far
- `api-gateway` — routing plus JWT validation at the edge
- `auth-service` — HS256, hardcoded users, `POST /api/auth/login`

**Then close.** T033 and the FR-011 gap. The profile tables in the root `README.md`,
`infra/README.md`, and `quickstart.md` all say six components and name Eureka as deferred — update
all three together, or they drift.

---

## Step 8 — Resilience4j

**Resolve this before writing the spec.**

The brief asks for a circuit breaker on the "inventory → payment path", and that hop is **Kafka**. It
is asynchronous and there is no synchronous call to wrap: a circuit breaker has nothing to open, a
time limiter has nothing to time, and a retry would mean republishing a message the broker already
holds.

Decide where these patterns genuinely belong. Gateway-to-service calls are real HTTP and are the
honest home for the circuit breaker and time limiter. Fallbacks that emit a compensating event belong
to consumers, not to the broker hop.

**Settings.** Circuit breaker at a 50% failure rate over a 10-call sliding window, 10 s open. Retry at
3 attempts with exponential backoff — **idempotent operations only**. Time limiter at 3 s.

---

## Step 9 — the k6 load test

**You install** k6. Principle V keeps installation yours.

**Build.** `loadtest/booking.js` — 1000 virtual users booking from a pool of 10 seats simultaneously,
asserting **exactly 10 succeed, 990 are rejected, and zero seats are double-booked**. Plus a
`make loadtest` target.

**Also.** The constitution requires an explicit latency budget for checkout in this step's plan,
validated by this test. Define the number before running it, or the result is an observation rather
than a pass.

**What this actually tests.** This is the payoff for the Lua script and the three partitions. Exactly
10 successes out of 1000 attempts is a claim about the atomicity of the seat lock — if the Lua script
is not genuinely all-or-nothing, this is the step that finds out.

---

## Step 10 — Helm and Minikube

**You install** minikube and helm.

**Build.** `helm/ticket-marketplace` — one chart, one `values.yaml` driving image, replicas, env, and
resources for every service, with a Deployment and a Service per microservice. Ingress, autoscaling,
and secrets management are explicitly out of scope.

**Watch the memory.** The full environment is about 3.1 GiB before any application service starts.
Minikube needs its own allocation on top, and the per-container limits from the Compose file should
carry across as resource requests and limits rather than being reinvented.

---

## Step 11 — the README the project is for

**Write.**

- An ASCII saga diagram — happy path and both failure paths
- Why choreography over orchestration
- Why an outbox, and why a Lua script rather than a SETNX loop
- The idempotency strategy and the read model's consistency window
- A placeholder for the k6 output, and local run instructions

**Also.** Flip the roadmap table in the root `README.md` as each step lands rather than all at the
end — it is the one place a reader checks to see whether the document is current.

**Where the material already is.** Most of these arguments have been written in `docs/tasks/` as the
decisions were made. This step is largely assembly and editing, not fresh justification, which is the
whole reason those documents were written at the time.

---

## Carried into every service step

Not tasks in any one step. They apply each time a new service module appears, and forgetting one is
how seven services drift apart.

- **Registration costs one line.** A `<module>` entry in the root pom plus the module's own pom, and
  nothing else — no compiler settings, no dependency versions, no test plugin configuration. That was
  measured in T051; if a service needs more, something has regressed.
- **Each service brings its own Dockerfile and `application.yml`**, and exposes
  `/actuator/prometheus`.
- **Add a Prometheus scrape job per service.** `infra/prometheus/prometheus.yml` already carries
  commented templates — use `host.docker.internal`, never `localhost`, because from inside that
  container localhost is Prometheus itself.
- **Every consumer needs the idempotency guard and a dead-letter route.** Delivery is at-least-once,
  so a consumer without the `processed_events` check will double-apply a redelivered message.
- **Integration tests use Testcontainers**, run under `verify`, and already work — the
  `docker.api.version` override that Docker 29 requires is set at the build root.
- **One task, one commit, one explainer.**

---

*Written at the close of build step 1. The traps recorded here were found while building it; new ones
will appear, and they get recorded the same way.*
