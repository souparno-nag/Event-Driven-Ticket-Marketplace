# Phase 0 Research: Event Contracts & Local Foundation

The stack was fixed by the project brief, so this phase resolves the open *design* decisions
inside that stack rather than choosing technologies. Each entry records what was chosen, why, and
what was rejected.

---

## R1. Contract representation: Java records

**Decision**: The seven message types are Java 21 `record` declarations, with no Lombok in the
contract module.

**Rationale**: Records give the constructor, accessors, `equals`, `hashCode`, and `toString` from
the language itself, and are immutable by default — which is exactly FR-005. They add no
dependency, keeping FR-010's framework-free requirement trivially true. A compact canonical
constructor gives a natural place to defensively copy collections and validate. For a codebase
whose stated purpose is being read cold in an interview, a record header *is* the schema on one
screen.

**Alternatives considered**:
- *Lombok `@Value`*: equivalent immutability but reintroduces an annotation processor into a
  module whose selling point is having no dependencies, and produces `getX()` accessors that
  suggest a mutable bean.
- *Hand-written immutable classes*: roughly 60 lines of ceremony per type, 400+ lines total, with
  hand-maintained `equals`/`hashCode` that can silently drift from the field list.

**Note**: Record accessors are `orderId()`, not `getOrderId()`. Jackson handles this natively; the
naming is uniform across all seven types so the wire contract stays predictable.

---

## R2. Envelope by duplication, not inheritance

**Decision**: The four envelope fields (`messageId`, `sagaId`, `occurredAt`, `schemaVersion`) are
repeated as components on each of the seven records. No shared base type, no generic
`Envelope<T>` wrapper.

**Rationale**: Records cannot extend a class, so inheritance is off the table regardless. More
importantly, Constitution Principle I explicitly prefers duplication over speculative
abstraction. A `sealed interface SagaEvent` is added for exhaustive `switch` handling in
consumers, but it declares only accessor signatures — it carries no state and no behaviour.

**Alternatives considered**:
- *`Envelope<T>(meta, payload)` wrapper*: produces nested JSON, forcing every consumer to unwrap
  before reading a business field, and complicates schema evolution for no gain at seven types.

---

## R3. Jackson configuration

**Decision**: Register `JavaTimeModule`; serialize `Instant` as ISO-8601 strings with
`WRITE_DATES_AS_TIMESTAMPS` disabled; enable `FAIL_ON_UNKNOWN_PROPERTIES = false`; represent
money as `BigDecimal` with `WRITE_BIGDECIMAL_AS_PLAIN` enabled.

**Rationale**: ISO-8601 strings keep messages human-readable when inspected on a channel, which
matters for a demo, and preserve nanosecond precision so the FR-006 round-trip equality assertion
holds. Disabling failure on unknown properties is FR-007 directly. `BigDecimal` written plain
avoids scientific notation, so the payment decline rule (last minor-unit digit is 7) and the load
test both compare exact values.

**Alternatives considered**:
- *Epoch millis for timestamps*: compact, but loses sub-millisecond precision and makes raw
  messages unreadable during a live demo.
- *`double` for money*: rejected outright — binary floating point cannot represent decimal
  currency exactly, which would make the decline rule non-deterministic.

**Caution carried into implementation**: `Instant` round-trips at nanosecond precision in Java but
PostgreSQL `timestamptz` stores microseconds. Truncation is invisible until a value crosses the
database in a later step. The round-trip test asserts on pure serialization here; equality
comparisons that involve stored timestamps should truncate to microseconds from step 2 onward.

---

## R4. Channel provisioning via `NewTopic` beans

**Decision**: Declare all fourteen channels as `NewTopic` beans, in one configuration class in the
contract-adjacent infrastructure of each service, driven by the constants in `Topics.java`.

**Rationale**: Spring's `KafkaAdmin` creates declared topics at startup and treats
already-existing topics as success, which satisfies FR-021's idempotency requirement with no
custom logic. Channel names live in version control next to the contracts they carry, so a
rename is a compile error rather than a runtime mystery.

**Alternatives considered**:
- *Broker auto-create*: creates topics on first reference with default partition counts, silently
  defeating FR-027's multi-partition requirement.

### Amendment — provisioning in step 1 specifically

The decision above holds from step 2 onward, but it cannot satisfy SC-009 in *this* step.
`KafkaAdmin` creates declared topics when a Spring application context starts, and step 1 contains
no Spring application — `common-events` is deliberately framework-free (FR-010). Yet SC-009
requires fourteen channels to exist after `make up`, with no service built yet.

**Resolution**: step 1 provisions channels with a one-shot `kafka-init` Compose service running
`kafka-topics.sh --if-not-exists` for each name. From step 2, services additionally declare
`NewTopic` beans; because both paths are idempotent, they coexist safely and the beans become the
long-term source of truth.

**Cost, and how it is covered**: the init script is a second place channel names are written, so
it can drift from `Topics.java`. This was the original reason for rejecting an init container, and
it is a real risk rather than a hypothetical one. `TopicNameDriftTest` (task T045) asserts the
constants and the provisioned set agree, converting a silent drift into a failing build.

**Alternative rejected**: adding a dedicated `topic-provisioner` Spring Boot module in step 1.
It would keep `Topics.java` as the single source, but introduces a module the project brief does
not list, and creates a chicken-and-egg problem — `make up` would depend on a built jar, breaking
the property that the environment starts from a clean checkout without a prior build.

---

## R5. Dead-letter channel naming

**Decision**: Each dead-letter channel is the message channel's name suffixed with `.DLT`.

**Rationale**: This is the default of Spring Kafka's `DeadLetterPublishingRecoverer`, so the
wiring is near-zero configuration and matches what any Spring-familiar reader expects. Fourteen
channels total, satisfying FR-020 and SC-009.

**Alternatives considered**:
- *A single shared dead-letter channel*: fewer channels, but mixes message shapes so inspection
  and replay tooling must sniff each record's type.
- *No dead-letter channel*: a permanently unprocessable message blocks its partition, and because
  messages are keyed by saga id (FR-026), it would block every order that hashes to that
  partition — not just the poisoned one.

---

## R6. Partition count and keying

**Decision**: Three partitions per channel, replication factor 1, every message keyed by its
`sagaId`.

**Rationale**: Keying by `sagaId` puts all of one order's messages on one partition, giving the
strict per-order ordering FR-026 requires, while three partitions let unrelated orders proceed
concurrently (FR-027) so the later load test exercises genuine contention rather than a queue.
Replication factor 1 is forced by the single-broker local environment.

**Alternatives considered**:
- *One partition*: total ordering, but serializes all traffic and makes the concurrency test
  meaningless.
- *Keying by show id*: naturally serializes seat contention, but the load test deliberately
  hammers one show, creating a hot partition that starves the others.

**Carried forward**: replication factor 1 means no fault tolerance. Acceptable for a local demo,
but it should be called out in the README rather than presented as a production topology.

---

## R7. Kafka in KRaft mode, single node

**Decision**: One Kafka container in KRaft combined mode (broker + controller in one process),
with a fixed `CLUSTER_ID` and a named volume for its data directory.

**Rationale**: Removes the ZooKeeper container entirely, which the brief requires, and cuts both
startup time and memory. A fixed cluster id means teardown and restart reproduces the same
cluster rather than failing on a metadata mismatch — which is what FR-015 and SC-005 measure.

**Pitfall to avoid**: mixing a leftover named volume with a changed cluster id produces a cryptic
metadata mismatch on startup. The `make down` target removes volumes so the reset is clean.

---

## R8. Health checks that mean "ready", not "running"

**Decision**: Every component declares a `healthcheck` probing actual service readiness, and
dependents use `depends_on: condition: service_healthy`.

| Component | Readiness probe |
|---|---|
| Kafka | `kafka-broker-api-versions` against the local listener |
| PostgreSQL | `pg_isready` for the configured user and database |
| Redis | `redis-cli ping` returning `PONG` |
| Elasticsearch | cluster health endpoint reporting `yellow` or `green` |
| Eureka | actuator health endpoint |
| Zipkin | its own health endpoint |
| Prometheus | `/-/healthy` |

**Rationale**: FR-012 requires readiness rather than liveness. A single-node Elasticsearch cluster
never reaches `green` because replicas cannot be allocated, so accepting `yellow` is correct here
— treating `yellow` as failure would hang startup forever, a classic single-node trap.

---

## R9. Container runtime: native Docker Engine, not Docker Desktop

**Decision**: Run the stack on the native Docker Engine (`docker context use default`) rather than
Docker Desktop. Native `docker.service` is already installed and enabled on the target machine;
the active context is currently `desktop-linux`.

**Rationale**: Docker Desktop on Linux runs every container inside a virtual machine with its own
kernel and a fixed memory ceiling. That costs roughly 1–2 GiB of overhead before a single
container starts, and caps the whole stack at whatever the VM was allocated regardless of how much
host memory is free. The native engine runs containers as namespaced processes directly on the
host kernel: no VM, no second kernel, no fixed ceiling.

This also corrects a tempting but mistaken optimisation. Moving PostgreSQL or Redis out of
containers and installing them natively saves almost nothing on the native engine, because a
Linux container is not a virtual machine — it is a process with namespaces and cgroups applied.
Containerised PostgreSQL and host-installed PostgreSQL consume nearly identical memory. The
saving people attribute to "getting out of Docker" on Linux is in practice the Docker Desktop VM
tax, and switching context removes it without giving up reproducible infrastructure.

**Alternatives considered**:
- *Stay on Docker Desktop and raise its VM memory allocation*: workable, but pays the VM overhead
  permanently and makes the environment's footprint depend on a GUI setting that is not in version
  control — which undermines FR-015's reproducibility requirement.
- *Install PostgreSQL, Redis, and Kafka natively on the host*: abandons one-command startup and
  reproducible teardown (FR-011, FR-015, SC-005) in exchange for a saving that does not exist once
  off the VM.

**Genuinely worth running natively**: the JVM services built in this project (Eureka now; order,
inventory, payment, and projection later) are worth running via `spring-boot:run` on the host
during development — for development-loop reasons rather than memory. Instant restarts, debugger
attach, and no image rebuild per change. They connect to containerised infrastructure over
`localhost`. Infrastructure in Compose, your own code on the host, is the standard hybrid.

**Secondary benefits of the native engine**, beyond reclaiming the VM's memory:

- *Bind mounts are direct filesystem access.* Docker Desktop proxies host files into its VM over
  virtiofs/gRPC-FUSE, which is markedly slower. This is felt whenever a host directory is mounted
  into a container — a shared `.m2` cache being the obvious case here.
- *Networking has no forwarding hop.* Published ports are reached at plain `localhost` rather than
  being forwarded through the VM's network stack.
- *`docker stats` reports against real host memory*, so the cgroup limits in R10 govern the full
  16 GiB rather than a slice of a VM.

### Known behavioural difference: `host.docker.internal`

`host.docker.internal` is a Docker Desktop convenience hostname and **does not resolve on the
native engine by default**. Any container needing to reach a service on the host must be given the
alias explicitly:

```yaml
prometheus:
  extra_hosts:
    - "host.docker.internal:host-gateway"
```

`host-gateway` is a built-in alias the engine resolves to the bridge gateway address.

**Where this bites**: step 8, when Prometheus running in a container scrapes Spring Boot services
running on the host under the hybrid development model above. The failure presents as a scrape
target stuck in `DOWN` with a DNS resolution error, which reads like a Prometheus configuration
problem rather than a runtime difference. Recorded here so it is designed in rather than
diagnosed later.

The reverse direction is unaffected: host processes reach published container ports at
`localhost`, and more directly than under Docker Desktop.

---

## R10. Memory budget: per-container limits with matched JVM heaps

**Decision**: Every service declares a hard `mem_limit`, a `mem_reservation` soft target, and
`memswap_limit` equal to `mem_limit`. Every JVM-based service additionally pins its heap to
roughly 60–70% of its container limit.

| Component | `mem_limit` | Heap / tuning |
|---|---|---|
| Kafka | 768 MiB | `KAFKA_HEAP_OPTS=-Xms512m -Xmx512m` |
| PostgreSQL | 256 MiB | `shared_buffers=128MB` |
| Redis | 96 MiB | `--maxmemory 64mb --maxmemory-policy noeviction` |
| Elasticsearch | 1 GiB | `ES_JAVA_OPTS=-Xms640m -Xmx640m`, single-node, security disabled |
| Eureka | 384 MiB | `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=65` |
| Zipkin | 256 MiB | `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=65` |
| Prometheus | 256 MiB | `--storage.tsdb.retention.time=6h` |
| **Full stack** | **~3 GiB** | |
| **`core` profile only** | **~1.1 GiB** | Kafka + PostgreSQL + Redis |

**Rationale**: Limits are enforced by the kernel through cgroups v2 (confirmed present on the
target machine, kernel 6.8), so they need no cooperation from the application. Their real value is
failure isolation: without a limit, one over-hungry container drags the entire host into swap and
everything degrades together; with one, that container is OOM-killed with exit code 137 and every
other component keeps running. A loud, attributable failure beats a slow, global one.

**The JVM interaction is the part that bites**, and it affects four of the seven components.
Since Java 10 the JVM reads its cgroup limit rather than host memory, but it defaults
`MaxHeapSize` to only **25%** of that limit — so a 1 GiB container with no explicit heap setting
gets a 256 MiB heap and wastes the rest. Both values must be set. The heap must also stay well
below the container limit, because metaspace, thread stacks, direct byte buffers, and GC
structures all live outside it; a heap set equal to the limit is killed the moment GC touches
off-heap memory.

`memswap_limit` equal to `mem_limit` forbids the container from swapping at all. Given the target
machine's swap is currently fully exhausted, failing fast is far more useful than crawling.

**Alternatives considered**:
- *No limits, rely on host memory*: the current situation — one component's growth degrades
  everything, with no signal identifying the culprit.
- *`deploy.resources.limits`*: the Compose-spec spelling. Honoured by Compose v2, but `mem_limit`
  is unambiguous outside Swarm and reads more plainly.

---

## R11. Selectable component sets via Compose profiles

**Decision**: Tag each service with Compose profiles and select the active set through
`COMPOSE_PROFILES` in a committed `infra/.env`.

| Profile | Components | Approx. footprint | Covers |
|---|---|---|---|
| `core` | Kafka, PostgreSQL, Redis | ~1.1 GiB | Build steps 1–5 |
| `obs` | Zipkin, Prometheus | ~0.5 GiB | Step 8 |
| `full` | All seven | ~3 GiB | Step 6 onward |

**Rationale**: Elasticsearch and Eureka are not needed until steps 6 and 7, yet they are among the
heaviest components. Profiles let a developer run only what the current step requires by changing
one line in `infra/.env`, with no edits to the Compose file and no commented-out blocks drifting
out of date. The selection is a committed file, so it is reviewable and reproducible.

This directly serves FR-017 (document the footprint) by making the footprint selectable rather
than fixed, and keeps SC-002's five-minute health target achievable on a constrained machine.

**Consequence to respect**: SC-009 requires all fourteen channels to exist, and channel creation
belongs to Kafka, which is in `core`. So channel validation works under every profile. But
Scenario 3's "every component healthy" check must assert against the *active* profile rather than
a hardcoded list of seven, or it will report false failures under `core`.

**Alternatives considered**:
- *Separate Compose files per set*: duplicates service definitions, which drift.
- *Multiple files composed with `-f`*: workable, but the override semantics are easy to get wrong
  and harder to read than a single tagged file.

---

## R12. Maven version floor

**Decision**: Target Maven 3.9.x and recommend the developer upgrade from the installed 3.6.3.

**Rationale**: Spring Boot 3.x names 3.6.3 as its exact minimum, so the installed version sits on
the floor with no margin. Several current plugin versions resolve dependencies in ways that behave
poorly on 3.6.x, and the failures surface as confusing resolution errors rather than a clear
"upgrade Maven" message.

**Mitigation if the developer prefers not to upgrade globally**: commit the Maven Wrapper
(`mvnw`) pinned to 3.9.x. The wrapper downloads its own Maven on first use, so the build becomes
reproducible regardless of what is installed. This is the recommended path — it also makes SC-004
("builds from a clean checkout") true for anyone else cloning the repository.

---

## R13. Lombok boundary

**Decision**: No Lombok in `common-events`. From step 2 onward: `@Slf4j` and
`@RequiredArgsConstructor` freely; `@Getter`/`@Setter` on JPA entities; never `@Data`,
`@EqualsAndHashCode`, or `@ToString` on an entity.

**Rationale**: Lombok's generated `equals`/`hashCode` on a JPA entity incorporates the generated
identifier, so an entity's hash code changes after `persist()` and corrupts any hash-based
collection holding it. `@ToString` walks lazy associations, causing surprise queries or
`LazyInitializationException`, and recurses infinitely across bidirectional relationships. The
restriction is scoped to the actual failure mode rather than banning the library outright.

---

## Resolved unknowns

Every item flagged in Technical Context is now resolved. No `NEEDS CLARIFICATION` markers remain.

The environment constraints that looked like blockers at the start of this phase are largely
answered by design rather than by asking the developer to free memory. R9 removes the Docker
Desktop VM overhead, R10 caps each component and stops one from starving the rest, and R11 makes
the heaviest components opt-in until the step that needs them. Together these take the working
footprint from an unbounded ~4+ GiB to **~1.1 GiB for build steps 1–5** and ~3 GiB for the full
stack.

What remains genuinely on the developer, per Constitution Principle V, is starting the Docker
daemon, switching the active context, and optionally committing the Maven wrapper. Steps are in
quickstart.md; none are performed automatically.
