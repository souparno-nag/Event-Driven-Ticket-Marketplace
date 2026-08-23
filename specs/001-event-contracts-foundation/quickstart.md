# Quickstart & Validation: Event Contracts & Local Foundation

How to bring this step up and prove it works. Details of *what* is built live in
[data-model.md](./data-model.md) and [contracts/](./contracts/); decisions and their reasoning
live in [research.md](./research.md).

---

## Prerequisites

Verified on this machine — **all clear as of 2026-08-22**.

| Prerequisite | Status |
|---|---|
| Java 21 | ✅ OpenJDK 21.0.11 |
| Docker engine + daemon | ✅ 29.7.2, active and enabled |
| Docker context | ✅ `default` — native engine, Ubuntu 22.04.5 |
| Docker group membership | ✅ |
| Docker Compose | ✅ v2.39.1 |
| cgroups v2 memory enforcement | ✅ verified: `-m 64m` ⇒ `memory.max = 67108864` |
| Visible memory | ✅ 15.3 GiB — full host, no VM slice |
| Maven | ⚠️ 3.6.3 — on Spring Boot's exact floor, see wrapper note below |
| k6 | ➖ absent, not needed until step 9 |

Per Constitution Principle V, anything below requiring installation or elevated privileges is
written as steps for you to run, never executed automatically.

### Re-establishing this setup (on a new machine, or if it regresses)

The daemon runs as `docker.service` and the client must point at it rather than at Docker
Desktop's socket.

```bash
sudo systemctl enable --now docker     # daemon running and starting on boot
sudo usermod -aG docker "$USER"        # then LOG OUT and back in — a new tab is not enough
docker context use default             # point the client at the native engine
```

Verify — the OS line must show your distribution, **not** "Docker Desktop":

```bash
docker context ls                                    # `default` should be starred
docker info --format '{{.OperatingSystem}} | cgroup {{.CgroupVersion}} | mem {{.MemTotal}}'
docker run --rm hello-world
docker run --rm -m 64m busybox cat /sys/fs/cgroup/memory.max   # expect 67108864
```

That last command is the one worth keeping: it proves cgroup limits are actually enforced, which
is what every `mem_limit` in the Compose file depends on.

### Why the native engine rather than Docker Desktop

On Linux, Docker Desktop runs every container inside a **virtual machine** — a second kernel with
1–2 GiB of overhead and a fixed memory ceiling that applies no matter how much host memory is
free. The native engine runs containers as processes on your own kernel: no VM, no ceiling.

This is also why **moving PostgreSQL or Redis out of Docker would not have helped.** A Linux
container is not a VM — it is a process with namespaces and cgroups applied. Containerised
PostgreSQL and apt-installed PostgreSQL use nearly the same memory. The overhead worth escaping is
the Desktop VM, and switching context escapes it without giving up one-command startup.

On Linux the native engine is the standard runtime — Docker Desktop for Linux is a convenience
wrapper added in 2022, and servers and CI runners overwhelmingly run the engine directly. You lose
the Desktop GUI (`docker ps` and `docker stats` cover it, or install `lazydocker` for a TUI) and
Desktop's bundled Kubernetes, which is irrelevant here because step 10 targets Minikube either
way. Bind mounts and `localhost` networking both get faster.

**One behavioural difference to remember for step 8**: `host.docker.internal` is a Desktop-only
hostname and does not resolve on the native engine. A container that needs to reach a service on
your host — Prometheus scraping a host-run Spring Boot service, for instance — needs the alias
declared explicitly:

```yaml
prometheus:
  extra_hosts:
    - "host.docker.internal:host-gateway"
```

The symptom if it is missed is a scrape target stuck in `DOWN` with a DNS error, which looks like
a Prometheus misconfiguration rather than a runtime difference. The reverse direction is fine:
host processes reach published container ports at plain `localhost`.

### How much memory you actually need

With the native engine plus the limits and profiles built into the Compose file, the `core`
profile needs only about **1.1 GiB**, not the 6 GiB an untuned stack would demand.

```bash
free -h
ps -eo pid,rss,comm --sort=-rss | head -15    # what is holding the memory
```

Your swap is currently fully exhausted, so the machine is already thrashing — worth closing a few
Chrome windows regardless. Aim for ~2 GiB available before `make up`; that comfortably covers
steps 1 through 5.

### How the memory budget is enforced

Every service declares a kernel-enforced hard cap, so one component cannot starve the others:

```yaml
kafka:
  mem_limit: 768m           # hard cap, enforced by cgroups v2
  mem_reservation: 512m     # soft target under contention
  memswap_limit: 768m       # equal to mem_limit ⇒ no swapping for this container
  environment:
    KAFKA_HEAP_OPTS: "-Xms512m -Xmx512m"    # ~66% of the cap
```

**The JVM detail matters** and affects Kafka, Elasticsearch, Eureka, and Zipkin. Since Java 10 the
JVM reads its cgroup limit rather than host memory — but defaults its heap to only **25%** of that
limit. Setting `mem_limit` alone would leave most of the container unused. Setting the heap
*equal* to the limit is worse: metaspace, thread stacks, and direct buffers live outside the heap,
so the kernel kills the process as soon as GC touches them. Roughly 60–70% is the working ratio.

When a container does exceed its cap:

```bash
docker stats --no-stream
docker inspect <container> --format '{{.State.OOMKilled}} {{.State.ExitCode}}'
```

Exit code **137** with `OOMKilled: true` means it hit the limit. That is the desired outcome — one
container dies loudly and attributably, instead of the whole host sliding into swap.

### Choosing what runs — Compose profiles

One line in `infra/.env` selects the component set:

```bash
COMPOSE_PROFILES=core      # core | obs | full
```

| Profile | Components | Footprint | Use for |
|---|---|---|---|
| `core` | Kafka, PostgreSQL, Redis | ~1.1 GiB | Build steps 1–5 |
| `obs` | Zipkin, Prometheus | ~0.5 GiB | Step 8 |
| `full` | All seven | ~3 GiB | Step 6 onward |

Elasticsearch and Eureka are the two heaviest components and are not needed until steps 6 and 7,
so `core` is the right default for everything this step validates. Change the one line and re-run
`make up` — no Compose edits.

### Optional — run your own services on the host

From step 2 onward, the JVM services you write are worth running outside Compose during
development:

```bash
./mvnw -pl order-service spring-boot:run
```

Instant restarts, debugger attach, no image rebuild per change. They reach the containerised
infrastructure over `localhost`. Infrastructure in Compose, your code on the host, is the standard
hybrid — and it is a development-loop benefit, not a memory one.

### Recommendation — Maven wrapper

Maven 3.6.3 (2020) is Spring Boot 3.x's exact minimum, with no margin; some current plugins fail
on it with confusing dependency-resolution errors rather than a clear version message. Rather
than upgrading system-wide, commit the wrapper:

```bash
mvn wrapper:wrapper -Dmaven=3.9.9
```

Then use `./mvnw` everywhere instead of `mvn`. This also makes SC-004 true for anyone else
cloning the repository, since the build no longer depends on what they happen to have installed.

---

## Bring it up

```bash
make up          # docker compose up -d, honouring COMPOSE_PROFILES from infra/.env
make health      # per-component health, one line each, for the active profile
make build       # ./mvnw clean verify at the root
make down        # stop and remove volumes — the clean reset
```

`make up` returns once every health check for the **active profile** passes. Under `core` expect
under a minute after images are cached; under `full` expect **2–5 minutes** on a first run while
images pull, with Elasticsearch the slowest to report healthy.

### Port map

| Component | Port |
|---|---|
| Kafka | 9092 |
| PostgreSQL | 5432 |
| Redis | 6379 |
| Elasticsearch | 9200 |
| Eureka | 8761 |
| Zipkin | 9411 |
| Prometheus | 9090 |

A port conflict surfaces as a container that exits immediately. `docker compose logs <service>`
names the conflict; `sudo lsof -i :<port>` identifies the process holding it.

---

## Validation scenarios

Each maps to acceptance criteria in [spec.md](./spec.md). Run in order — Scenario 1 needs no
containers at all.

### Scenario 1 — Contracts round-trip (US1, SC-003, SC-006)

**Needs**: nothing running.

```bash
./mvnw -pl common-events test
```

**Expect**: all seven message types serialize and deserialize back to an equal object, including
nested `seatIds` and `Instant` precision. Also asserts a payload carrying an unrecognised extra
field deserializes without error (FR-007), and that no contract field name is ambiguous between
message identity and show identity (SC-007).

**Fails if**: `jackson-datatype-jsr310` is not registered — `Instant` then serializes as an object
rather than an ISO-8601 string and equality fails.

### Scenario 2 — Framework-free contract module (US1, FR-010)

```bash
./mvnw -pl common-events dependency:tree
```

**Expect**: Jackson annotations and test-scope dependencies only. **No** `org.springframework.*`
and **no** `org.projectlombok`. If Spring appears, something leaked in and any module can now no
longer depend on this one freely.

### Scenario 3 — Environment healthy (US2, SC-001, SC-002)

```bash
make up && make health
```

**Expect**: every component **in the active profile** reports healthy within 5 minutes, each on
its own line so a single failure is identifiable without reading raw logs (FR-016).

**Important**: `make health` must derive its component list from the active profile, not from a
hardcoded list of seven. A hardcoded list reports false failures under `core`, where
Elasticsearch, Eureka, Zipkin, and Prometheus are intentionally absent. Validate SC-001 and
SC-002 against `COMPOSE_PROFILES=full` at least once.

**Note**: Elasticsearch reports **yellow**, and that is correct. A single-node cluster cannot
allocate replicas, so it never reaches green. Treating yellow as failure would hang startup
forever.

**If a container is restarting rather than reporting healthy**, check whether it hit its memory
cap before suspecting configuration:

```bash
docker inspect <container> --format '{{.State.OOMKilled}} {{.State.ExitCode}}'
```

### Scenario 4 — Reproducible restart (US2, SC-005)

```bash
for i in $(seq 1 10); do
  make down >/dev/null 2>&1
  make up   >/dev/null 2>&1 || { echo "FAILED on cycle $i"; break; }
  echo "cycle $i healthy"
done
```

**Expect**: ten consecutive clean cycles. This is the test that catches state leaking between
runs — most often a stale Kafka volume whose cluster id no longer matches, which produces a
cryptic metadata mismatch rather than an obvious error.

**Budget time**: at roughly a minute per cycle this runs 10–15 minutes.

### Scenario 5 — Channels provisioned (US3, SC-009, FR-020, FR-021)

```bash
docker compose -f infra/docker-compose.yml exec kafka \
  kafka-topics --bootstrap-server localhost:9092 --list | sort
```

**Expect**: exactly **fourteen** channels — the seven message channels plus a `.DLT` for each.
Run `make up` a second time without tearing down and confirm it still succeeds, proving creation
is idempotent (FR-021).

```bash
docker compose -f infra/docker-compose.yml exec kafka \
  kafka-topics --bootstrap-server localhost:9092 --describe --topic order.created
```

**Expect**: `PartitionCount: 3` (FR-027), `ReplicationFactor: 1` — the latter forced by the
single-broker local setup.

### Scenario 6 — Per-order ordering under concurrency (SC-011)

**Needs**: Kafka running. This is the constitution's required concurrency test for this step.

```bash
./mvnw -pl common-events -Dtest=OrderingGuaranteeIT verify
```

**Expect**: 100 orders published concurrently, each with several messages keyed by its `sagaId`;
every order's messages are observed in production order, with zero out-of-order deliveries within
any single order. Messages from *different* orders interleave freely — that is the point, and
proves partitioning is real rather than an accidental single-partition queue.

### Scenario 7 — Root build (US3, SC-004, SC-008)

```bash
./mvnw clean verify
```

**Expect**: every registered module compiles and tests in dependency order from one invocation.
To verify SC-008, add an empty module and confirm the only edits needed are one `<module>` line
in the root POM and the new module's own POM.

---

## Definition of Done

- [ ] All seven contracts exist as records; `dependency:tree` shows no framework dependency
- [ ] Round-trip test passes for all seven types, including the unknown-field case
- [ ] No contract field is ambiguous between message identity and show identity
- [ ] `SeatsReserved` carries `lockExpiresAt`, validated as strictly after `occurredAt`
- [ ] `make up` reaches full health from a clean checkout in under 5 minutes, verified at least
      once under `COMPOSE_PROFILES=full`
- [ ] `make health` reports each component individually and derives its list from the active
      profile rather than a hardcoded seven
- [ ] Every service declares `mem_limit`, `mem_reservation`, and `memswap_limit`; every JVM
      service pins its heap to roughly 60–70% of its cap
- [ ] `core` profile starts within ~1.1 GiB, confirmed via `docker stats`
- [ ] Ten teardown/restart cycles all succeed
- [ ] Exactly fourteen channels exist, 3 partitions each, created idempotently
- [ ] Ordering test passes across 100 concurrent orders
- [ ] `./mvnw clean verify` succeeds from a clean checkout
- [ ] `infra/README.md` documents the profile table, per-component memory budget, and port map
- [ ] JSON schemas in `contracts/` match the records field-for-field — a manual review step, as
      there is no registry enforcing it

---

## Out of scope

No service logic, no database schema, no HTTP endpoint, no outbox, no seat locking. PostgreSQL,
Redis, and Elasticsearch are started and health-checked but hold no application data. Those
arrive from step 2 onward.
