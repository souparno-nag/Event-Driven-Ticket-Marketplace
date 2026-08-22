# T029 — Kafka in Docker Compose

**What this task did:** created `infra/docker-compose.yml` with its first service — Kafka, running in
KRaft mode with a memory limit, a real health check, and a profile tag.

This begins Phase 4, which touches no Java at all.

---

## What Docker Compose is doing here

Kafka, PostgreSQL, Redis and the rest are all servers that would otherwise have to be installed on
your machine, each with its own version, config file location, and start-up ritual.

Compose replaces that with one YAML file describing containers, and `docker compose up` to start
them. Two properties matter more than convenience:

- **Reproducible.** The file is committed, so everyone gets identical versions and settings.
- **Disposable.** `docker compose down -v` removes the containers *and* their data. Getting back to
  a known-good state is one command, not an afternoon.

Only Kafka is in the file so far. T030–T036 add the other six.

---

## KRaft: Kafka without ZooKeeper

Kafka historically needed a second system, **ZooKeeper**, to track cluster metadata — which brokers
exist, who leads which partition. That meant running two clusters to get one.

**KRaft** (Kafka Raft) moves that metadata into Kafka itself. The brief requires it (**FR-014**), and
it removes a whole container plus its memory.

In KRaft there are two roles:

| Role | Job |
|---|---|
| **broker** | Stores messages, serves producers and consumers |
| **controller** | Tracks metadata and elects partition leaders |

```yaml
KAFKA_PROCESS_ROLES: broker,controller
```

That is **combined mode** — one process doing both. A production cluster separates them, so the
controller quorum survives broker restarts. A single node has no quorum to protect, and a second
process would cost another ~300 MiB, so combining is right here and wrong at scale.

### The cluster id is hardcoded on purpose

```yaml
CLUSTER_ID: 5L6g3nShT-eMCtK--X86sw
```

KRaft stamps this id into its data directory the first time it starts, and refuses to start against
a directory containing a *different* one.

Generate it randomly and every restart is a coin flip: fresh volume, works; surviving volume,
cryptic metadata-mismatch error. A fixed id means the same cluster comes back every time — which is
literally what **FR-015** and **SC-005** measure ("teardown and restart reaches the same healthy
state"). It also means `make down` must remove the volume, which is why that target will use `-v`.

---

## The listener configuration — the classic Docker trap

This is the part that catches everyone, and it is worth understanding rather than copying.

**A Kafka client does not keep talking to the address it connected to.** It bootstraps against one
address, and the broker replies *"here is my real address, reconnect there"*. That reply is the
**advertised listener**.

So a single listener cannot serve both worlds:

| Advertised as | Container clients | Host processes |
|---|---|---|
| `kafka:9092` | ✅ Docker DNS resolves `kafka` | ❌ your machine has no such host |
| `localhost:9092` | ❌ resolves to the *container itself* | ✅ works |

The second failure is the nastier one, because the initial connection **succeeds** and only the
reconnect fails — producing a client that hangs or reports a broker it cannot reach, while `docker
ps` shows everything healthy.

The fix is two listeners on the same broker:

```yaml
KAFKA_LISTENERS:            INTERNAL://0.0.0.0:29092,HOST://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093
KAFKA_ADVERTISED_LISTENERS: INTERNAL://kafka:29092,HOST://localhost:9092
```

`LISTENERS` is where the broker **binds** (`0.0.0.0`, all interfaces). `ADVERTISED_LISTENERS` is what
it **tells clients**. Containers come in the INTERNAL door and are told `kafka:29092`; processes on
your machine come in the HOST door and are told `localhost:9092`. Same broker, two doors, each
answering with an address that works from where the caller stands.

Both are needed here: the topic-creation container in T045 runs *inside* the network, while services
run from an IDE in steps 2–7 and the Testcontainers test run *on the host*.

Only 9092 is published to the host. The controller and inter-broker listeners stay inside the
network, because nothing outside needs them.

---

## Auto-creation is switched off, deliberately

```yaml
KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false"
```

By default Kafka creates a topic the first time anyone mentions it. Convenient, and wrong here.

An auto-created topic gets **default** settings — one partition. This project needs three
(**FR-027**), because per-order ordering comes from keying messages to partitions and parallelism
comes from having more than one of them.

And the failure would be invisible. Everything works. Messages flow. You simply never get the
concurrency the load test exists to prove, and nothing anywhere reports a problem. Switching
auto-creation off converts that into an honest error the first time something references a channel
that was never provisioned.

Explicit provisioning arrives in T044.

---

## Memory: the limit and the JVM heap must both be set

```yaml
mem_limit: 768m
mem_reservation: 512m
memswap_limit: 768m
environment:
  KAFKA_HEAP_OPTS: -Xms512m -Xmx512m
```

**`mem_limit`** is a hard ceiling the kernel enforces through cgroups. Exceed it and the container
is killed with exit code **137** — worth memorising, since it looks like a mysterious crash until
you know it means "OOM-killed".

The point is *failure isolation*. With no limit, one greedy container drags the whole host into
swap and everything degrades together, with nothing identifying the culprit. With a limit, that one
container dies loudly and everything else keeps serving.

**`memswap_limit` equal to `mem_limit`** forbids swapping. Counter-intuitive — swap looks like a
safety net — but a container thrashing on swap is slower and harder to diagnose than one that dies.
Fail fast beats crawl.

**`KAFKA_HEAP_OPTS` is not redundant with `mem_limit`**, and this is the trap:

> Since Java 10 the JVM reads its cgroup limit rather than host memory — good. But it defaults
> `MaxHeapSize` to only **25%** of that limit. A 768 MiB container with no heap setting runs a
> ~192 MiB heap and wastes the rest.

So both must be set. And the heap must stay comfortably *below* the container limit, because
metaspace, thread stacks, direct byte buffers, and GC structures all live **outside** the heap. A
heap set equal to the limit is killed the moment GC touches off-heap memory. 512 MiB of heap inside
768 MiB total leaves that headroom.

---

## Health check: ready, not merely running

```yaml
test: ["CMD", "kafka-broker-api-versions", "--bootstrap-server", "localhost:9092"]
start_period: 30s
```

**FR-012** demands readiness, not liveness, and the distinction is what makes `depends_on` useful.

Kafka's process listens on its port long before it can serve metadata. A port check would report
healthy during that window, and anything that started on the strength of it would fail. This probe
asks the broker to answer a real API request — the same thing a client needs — so "healthy" means
"a client would succeed".

**`start_period`** handles first-run slowness. KRaft formats its storage directory the first time,
so the first boot is slower than later ones. Failures during the start period do not count toward
`retries`, so a slow first start does not mark the container unhealthy — while a genuinely broken
broker still fails once the window closes.

Once T038 wires up `depends_on: condition: service_healthy`, this probe is what stops dependants
from starting too early.

---

## Profiles: only run what this step needs

```yaml
profiles: ["core", "full"]
```

A service with a `profiles` tag starts **only** when one of its profiles is active. Kafka is in
`core` and `full`, so it runs for both.

| Profile | Components | Footprint | For |
|---|---|---|---|
| `core` | Kafka, PostgreSQL, Redis | ~1.1 GiB | build steps 1–5 |
| `obs` | Zipkin, Prometheus | ~0.5 GiB | build step 8 |
| `full` | everything | ~3 GiB | build step 6 onward |

Elasticsearch and Eureka are among the heaviest components and are not needed until steps 6 and 7.
Profiles mean you change one line in `infra/.env` rather than commenting blocks out — and
commented-out blocks always drift.

One consequence to remember, and T040 depends on it: `make health` must derive its list from the
**active** profile. A hardcoded list of seven reports false failures under `core`.

---

## Also in this commit: `.dockerignore`

The implement workflow checks for ignore files matching the project's tooling, and Docker is now
part of it. Service Dockerfiles arrive from step 2 and will build from the repository root, since a
module needs the parent pom above it — so the build context would otherwise include `target/`,
`.git/`, `docs/`, and `specs/`.

Two reasons that matters: build context is uploaded to the Docker daemon on every build, and
`docs/` changing on every commit would invalidate the layer cache constantly. Excluding `target/`
also prevents a stale local jar being copied into an image that was supposed to build its own.

---

## Try it yourself

I validated the file's syntax and resolved values (a parse-only check that starts nothing):

```bash
docker compose -f infra/docker-compose.yml --profile core config
```

Two things confirmed there: `mem_limit` resolves to `805306368` — exactly 768 MiB, so the unit was
parsed as expected — and profile gating works:

```bash
docker compose -f infra/docker-compose.yml config --services              # (nothing)
docker compose -f infra/docker-compose.yml --profile core config --services   # kafka
```

An untagged service would start under every profile; the empty first result proves the tag is doing
its job.

**Actually starting it** is yours to run, and there is no `make up` yet — that arrives in T039:

```bash
docker compose -f infra/docker-compose.yml --profile core up -d
docker compose -f infra/docker-compose.yml ps          # wait for (healthy)
```

**Expect**: `kafka` reaching `Up (healthy)` within about 30–40 seconds. The first run also pulls the
image, which takes longer.

Worth trying while it is up — prove the two listeners really do behave differently:

```bash
# from your machine, via the HOST listener
docker exec kafka kafka-broker-api-versions --bootstrap-server localhost:9092 | head -3

# what the broker advertises
docker exec kafka kafka-metadata-quorum --bootstrap-server localhost:9092 describe --status
```

Tear down with:

```bash
docker compose -f infra/docker-compose.yml --profile core down -v
```

The `-v` matters — it removes the named volume, which is what makes the fixed `CLUSTER_ID` give a
clean reset rather than a metadata mismatch.

---

## What comes next

**T030–T036** add the remaining six services to this same file — PostgreSQL, Redis, Elasticsearch,
Eureka, Zipkin, Prometheus — each with its own limit, probe, and profile tag. They are marked `[P]`
because they touch independent concerns, though they share one file.
