# Implementation Plan: Event Contracts & Local Foundation

**Branch**: `001-event-contracts-foundation` | **Date**: 2026-08-22 | **Spec**: [spec.md](./spec.md)

**Input**: Feature specification from `/specs/001-event-contracts-foundation/spec.md`

## Summary

Deliver the foundation the remaining ten build steps stand on: a framework-free contract module
holding the seven saga messages as immutable Java records, a Maven build root that compiles every
current and future module from one command, and a Docker Compose environment bringing up seven
backing components that each report healthy.

The technical approach is deliberately boring, because this step's value is stability rather than
cleverness. Contracts are Java 21 records so immutability, equality, and a readable schema come
from the language rather than from an annotation processor. Message channels and their paired
dead-letter channels are declared as beans so provisioning is idempotent and versioned with the
code. Every container declares a real readiness check so that "healthy" means "serving", not
"process started".

## Technical Context

The stack is fixed by the project brief rather than chosen here, so this section records it and
flags only the genuinely open items, which Phase 0 resolves.

**Language/Version**: Java 21 (verified present: OpenJDK 21.0.11)

**Primary Dependencies**: Spring Boot 3.3.x, Spring Cloud 2023.0.x, Spring for Apache Kafka,
Jackson (with `jackson-datatype-jsr310` for `Instant`), Lombok — used only where safe, and not at
all in the contract module

**Storage**: PostgreSQL 16, Redis 7, Elasticsearch 8 — provisioned and health-checked in this
step; no schemas or application access until later steps

**Testing**: JUnit 5, AssertJ, Testcontainers (Kafka module) for the ordering-guarantee test

**Target Platform**: Linux developer workstation running a local container runtime

**Project Type**: Multi-module Maven project — a shared library module plus infrastructure, with
service modules registered in later steps

**Performance Goals**: None user-facing in this step. The one measurable behavioural target is
SC-011: strict per-order message ordering across at least 100 concurrent orders.

**Constraints**: Full environment healthy within 5 minutes (SC-002) on a machine meeting the
documented memory floor. Contract module must remain framework-free (FR-010).

**Scale/Scope**: One shared module, one Compose file, fourteen message channels. No service logic.

### Environment findings (checked, not assumed)

| Prerequisite | Status | Action |
|---|---|---|
| Java 21 | ✅ OpenJDK 21.0.11 | None |
| Maven | ⚠️ 3.6.3 (released 2020) | Sits on Spring Boot 3.x's exact minimum. Commit the wrapper — R12 |
| Docker engine | ✅ 29.7.2, daemon active | Resolved |
| Docker context | ✅ `default` — native engine, Ubuntu 22.04.5 | Resolved; `docker info` confirms the host OS, not "Docker Desktop" |
| Docker group | ✅ user is a member | Resolved |
| Docker Compose | ✅ v2.39.1 | None |
| cgroups v2 + memory controller | ✅ kernel 6.8, **enforcement verified** | `docker run -m 64m` yields `memory.max = 67108864`, so R10's limits work as designed |
| Visible memory | ✅ 15.3 GiB — full host, no VM slice | Resolved by the context switch |
| k6 | ❌ absent | Not needed until build step 9 |

**All environment blockers are cleared.** The daemon was in fact running throughout; the original
"not reachable" reading was caused by the active context pointing at Docker Desktop's socket while
Desktop itself was not running. Switching to the native engine resolved it and simultaneously
removed the VM memory ceiling.

The memory situation, which initially looked like a blocker, is answered by design decisions
R9–R11 rather than by asking the developer to free several gigabytes:

- **R9** switches off Docker Desktop, whose Linux VM costs 1–2 GiB of overhead and imposes a fixed
  ceiling regardless of free host memory. The native engine runs containers as host processes.
- **R10** gives every component a kernel-enforced `mem_limit` with a matched JVM heap, so one
  component cannot starve the rest, and a component that misbehaves is OOM-killed with a clear
  exit code 137 instead of dragging the whole host into swap.
- **R11** makes Elasticsearch and Eureka opt-in via Compose profiles, since neither is needed
  until steps 6 and 7.

Net effect: **~1.1 GiB for build steps 1–5** under the `core` profile, ~3 GiB for the full stack —
against an untuned ~4+ GiB plus VM overhead.

Per Constitution Principle V, all resolution steps are handed to the developer in
[quickstart.md](./quickstart.md) rather than executed automatically.

## Constitution Check

*GATE: Must pass before Phase 0 research. Re-check after Phase 1 design.*

| Principle | Gate | Pre-Phase 0 | Post-Phase 1 |
|---|---|---|---|
| I. Code Quality | Single responsibility per module; public interfaces documented at definition; no speculative abstraction | ✅ Pass | ✅ Pass |
| II. Testing Standards | Automated unit tests for logic; contract round-trip tests; concurrency-focused test where shared state exists | ✅ Pass | ✅ Pass |
| III. UX Consistency | Consistent user-facing surfaces | ➖ Not applicable | ➖ Not applicable |
| IV. Performance | Latency budget for user-facing paths | ➖ Not applicable | ➖ Not applicable |
| V. Human-Gated Tooling | No autonomous install, credentialing, or provisioning | ✅ Pass | ✅ Pass |

**I — Code Quality**: The contract module has exactly one responsibility: define the wire
messages. It takes no framework dependency (FR-010), so it cannot accumulate behaviour. Each
record is self-documenting; the field list *is* the schema. No base classes or generic envelope
hierarchy — the envelope fields are repeated per record, which is duplication the constitution
explicitly prefers over premature abstraction.

**II — Testing Standards**: Round-trip serialization tests cover all seven types (SC-003), and a
Testcontainers test proves per-order ordering under 100 concurrent orders (SC-011) — which is the
concurrency-focused testing the constitution requires, applied at the first point shared ordering
guarantees exist. The constitution's producer/consumer integration-test rule attaches from step 2
onward, when the first real producer and consumer exist; noted rather than silently skipped.

**III & IV — Not applicable, with reason**: This step ships no user-facing surface and no
request-serving path, so there is no interaction to keep consistent and no latency to budget.
Both gates re-arm at the first step that serves a request. Recorded here so the omission is a
documented judgement rather than an oversight.

**V — Human-Gated Tooling**: The three blockers above are exactly the case this principle
governs. Starting a Docker daemon, upgrading Maven, and freeing system memory are all changes to
the developer's machine, so quickstart.md gives copy-pasteable steps and this plan performs none
of them.

**Result**: No violations. Complexity Tracking section omitted as it would be empty.

## Project Structure

### Documentation (this feature)

```text
specs/001-event-contracts-foundation/
├── plan.md              # This file
├── research.md          # Phase 0 output
├── data-model.md        # Phase 1 output
├── quickstart.md        # Phase 1 output — includes blocker resolution steps
├── contracts/           # Phase 1 output — the seven message schemas
│   ├── README.md
│   └── *.schema.json
├── checklists/
│   └── requirements.md  # From /speckit-specify + /speckit-clarify
└── tasks.md             # Created later by /speckit-tasks
```

### Source Code (repository root)

```text
pom.xml                          # Build root: module registry + dependencyManagement

common-events/
├── pom.xml                      # No Spring, no Lombok — plain Java + Jackson annotations
└── src/
    ├── main/java/com/marketplace/events/
    │   ├── OrderCreated.java        # record
    │   ├── SeatsReserved.java       # record, carries lockExpiresAt
    │   ├── SeatsRejected.java       # record, carries RejectionReason
    │   ├── PaymentSucceeded.java    # record
    │   ├── PaymentFailed.java       # record, carries PaymentFailureReason
    │   ├── OrderConfirmed.java      # record
    │   ├── OrderCancelled.java      # record, carries CancellationReason
    │   ├── RejectionReason.java     # enum
    │   ├── PaymentFailureReason.java# enum
    │   ├── CancellationReason.java  # enum
    │   └── Topics.java              # channel name constants, single source of truth
    └── test/java/com/marketplace/events/
        └── ContractRoundTripTest.java

infra/
├── docker-compose.yml           # 7 components + kafka-init: profiles, mem_limits, healthchecks
├── .env                         # COMPOSE_PROFILES — the one knob selecting the component set
├── kafka-init/create-topics.sh  # one-shot channel provisioning (R4 amendment)
├── prometheus/prometheus.yml
└── README.md                    # profile table, memory budget, port map

Makefile                         # up / down / health / build targets
```

**Structure Decision**: Multi-module Maven with the build root at the repository top. Only
`common-events` is registered now; steps 2 onward add sibling modules by appending one `<module>`
line, satisfying FR-022 and SC-008. Infrastructure lives in `infra/` rather than at the root so
the root stays a build descriptor and does not accumulate operational files as later steps add
Helm charts and load tests.

`Topics.java` sits in the contract module deliberately: channel names are part of the contract
between services, so a service cannot publish to a name a consumer does not know about.
