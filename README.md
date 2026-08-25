# Event-Driven Ticket Marketplace

A ticket-booking system built as a **choreography-based saga over Kafka** — no central orchestrator,
each service reacting to messages and publishing its own. It exists to demonstrate the patterns that
make distributed transactions work: the transactional outbox, idempotent consumers, compensating
actions, and a CQRS read model.

> ### Status: build step 2 of 11 — order acceptance and the transactional outbox
>
> What exists today is the **shared message contract library**, the **multi-module build**, a
> **one-command local environment**, and **`order-service`**: accept a booking, record it and the
> first saga event durably in one transaction, and read it back. There is still no seat locking, no
> payments, no compensation. Those arrive in build steps 3 through 5.
>
> This is stated up front because a README describing the finished system would be describing
> something you cannot run. The [roadmap](#roadmap) below says what is built and what is not.

---

## Prerequisites

Two things:

| | Why |
|---|---|
| **JDK 21** | The contracts use records and sealed interfaces. |
| **Docker** with Compose v2 | Runs the backing components, and the integration tests start a broker of their own. |

Maven is **not** a prerequisite — `./mvnw` downloads the exact version the project expects. That is
why every command below is `./mvnw` and never `mvn`.

Verified from a clean clone with an empty Maven cache: nothing else needs installing.

---

## Quickstart

```bash
git clone <this repository>
cd Event-Driven-Ticket-Marketplace

make up        # start the backing components, wait until every one is healthy
make health    # one line per component
make build     # compile and test everything
make down      # stop and delete data — a clean reset
```

`make up` returns only when every component passes its own readiness check, or fails after five
minutes. It then creates the fourteen Kafka channels the saga uses. First run pulls about 590 MB of
images and takes a few minutes; later runs take seconds.

If something does not come up, `make health` names the component and
[`infra/README.md`](infra/README.md) covers the common failures — including exit code 137, which is
the one you are most likely to meet.

---

## Choose what runs

Six components are defined, and until build step 6 half of them do nothing. Each carries a Compose
**profile**, and `COMPOSE_PROFILES` in [`infra/.env`](infra/.env) decides what starts.

| Profile | Components | Memory | Needed from |
|---|---|---|---|
| `core` *(default)* | Kafka, PostgreSQL, Redis | ~1.1 GiB | build steps 1–5 |
| `obs` | Zipkin, Prometheus | ~0.5 GiB | build step 8 |
| `full` | all of the above **+ Elasticsearch** | ~3.1 GiB | build step 6 onward |

Profiles combine, so `core,obs` runs five components. Override without editing the file:

```bash
COMPOSE_PROFILES=full make up
```

Ports, per-component memory limits, and diagnostics are in [`infra/README.md`](infra/README.md).

---

## What is in the repository

```
common-events/     Shared message contracts. Seven records, no framework dependencies.
order-service/     Accepts bookings, owns the Order aggregate and its transactional outbox.
infra/             docker-compose.yml, Kafka channel provisioning, environment docs.
docs/tasks/        A written explanation of every task, in order.
specs/             The specification driving the build.
Makefile           Everything above, as four commands.
```

### `common-events`

Seven message types as Java records implementing a sealed `SagaEvent` interface — so a `switch` over
them is checked for exhaustiveness, and adding an eighth breaks every consumer that has not handled
it, at compile time.

```
OrderCreated  →  SeatsReserved  →  PaymentSucceeded  →  OrderConfirmed
                 SeatsRejected      PaymentFailed        OrderCancelled
```

Every message carries the same four-field envelope: `messageId` (the consumer's idempotency key),
`sagaId` (equal to the order id, and the Kafka partition key), `occurredAt`, and `schemaVersion`.

The module has exactly **one** compile dependency — `jackson-annotations`. It deliberately carries no
Spring, so any service can depend on it without inheriting a framework.

### `order-service`

The first real service, and the front door of the marketplace. Runs on **port 8081**. It owns the
`Order` aggregate and the transactional outbox that announces it — the order row and the outbox row
recording `OrderCreated` are written in one transaction, so either both exist or neither does. A
background relay, polling every 500ms by default, drains that outbox onto Kafka once the transaction
has actually committed, and a booking is not confirmed here — this step ends the moment the message
is on its way; seat locking, payment, and confirmation arrive in later steps.

Submit a booking:

```bash
curl -i -X POST http://localhost:8081/api/orders \
  -H 'Content-Type: application/json' \
  -d '{"userId":"11111111-1111-1111-1111-111111111111",
       "showId":"22222222-2222-2222-2222-222222222222",
       "seatIds":["A1","A2"],
       "amount":"150.00"}'
```

Expect `202 Accepted`, a `Location` header, and `{"orderId": "...", "status": "PENDING"}` — accepted
for processing, not booked.

Read it back:

```bash
curl http://localhost:8081/api/orders/<orderId>
```

Expect every field returned unchanged, plus `createdAt`/`updatedAt` and the current `status`. An
identifier that isn't a well-formed UUID gets a `400`; a well-formed one nothing matches gets a `404`
— each with its own RFC 7807 problem `type`, so a caller can tell the two apart without parsing text.

Full HTTP contract: [`specs/002-order-service-outbox/contracts/orders-api.yaml`](specs/002-order-service-outbox/contracts/orders-api.yaml).

---

## Tests

```bash
./mvnw test      # unit tests only — fast, no Docker
./mvnw verify    # the above, plus integration tests against a real broker
```

`verify` starts a Kafka container and publishes 500 messages across 100 orders from 8 threads, then
asserts that every order's messages come back in the order they were sent — that is `common-events`'
own suite. `order-service`'s own `verify` starts PostgreSQL and Kafka containers of its own and
covers the outbox end to end: acceptance is genuinely atomic, the relay only marks a row sent after a
real broker acknowledgement, one poisoned row never stalls another order's, and concurrent relays
never send the same row twice. Integration tests are **not** behind an opt-in flag: a test nobody
runs by default is a test that rots.

---

## Roadmap

Build steps come from [`CLAUDE.md`](CLAUDE.md). Each one after this is its own specification.
[`docs/ROADMAP.md`](docs/ROADMAP.md) breaks the remaining ten down — scope, prerequisites, what to
write by hand, and the traps already identified in each.

| Step | Scope | Status |
|---|---|---|
| 1 | Contracts, build root, local environment | ✅ **done** |
| 2 | `order-service` with a transactional outbox | ✅ **done** |
| 3 | `inventory-service` — Redis seat locks via Lua | not started |
| 4 | `payment-service` and saga completion | not started |
| 5 | Compensation paths | not started |
| 6 | `projection-service` and the Elasticsearch read model | not started |
| 7 | API gateway, auth, and Eureka service registry | not started |
| 8 | Resilience4j | not started |
| 9–11 | k6 load test, Helm and Minikube, full documentation | not started |

The saga diagram, the argument for choreography over orchestration, and the rest of the design
rationale arrive with step 11. Until then the reasoning lives in `docs/tasks/`, one document per
task, written to be read by someone new to the technology rather than someone who already knows it.

---

## Specification

This project is built specification-first. Everything under
[`specs/001-event-contracts-foundation/`](specs/001-event-contracts-foundation/) came before the
code:

| Document | What it holds |
|---|---|
| [`spec.md`](specs/001-event-contracts-foundation/spec.md) | Requirements and measurable success criteria |
| [`plan.md`](specs/001-event-contracts-foundation/plan.md) | Technical approach |
| [`research.md`](specs/001-event-contracts-foundation/research.md) | Decisions, with the alternatives that were rejected |
| [`tasks.md`](specs/001-event-contracts-foundation/tasks.md) | The task breakdown, in build order |
| [`quickstart.md`](specs/001-event-contracts-foundation/quickstart.md) | Verification scenarios |
| [`contracts/`](specs/001-event-contracts-foundation/contracts/) | JSON Schema for each message — the normative wire format |

`.specify/memory/constitution.md` is the governing document; where anything else disagrees with it,
it wins.
