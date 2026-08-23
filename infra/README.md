# Local environment

The backing components the ticket marketplace runs against: Kafka, PostgreSQL, Redis,
Elasticsearch, Zipkin, and Prometheus. Everything is driven from the repository root through
`make`, so nothing here needs to be run by hand.

```bash
make up       # start the active profile, wait until every component is healthy
make health   # one line per component, so a single failure is identifiable
make logs     # follow all logs (make logs SERVICE=kafka for one)
make down     # stop and delete data — a clean reset
```

`make up` returns only once every health check passes, or fails after five minutes. It then
provisions the fourteen message channels — seven message types, each paired with a dead-letter
channel — via a one-shot job that exits when done. Channel creation is idempotent, so running
`make up` against an environment that already has them succeeds and changes nothing.

---

## Profiles: choose what runs

Running all six components costs about 3.1 GiB, and until build step 6 half of them do nothing.
Each service is tagged with a **profile**, and the active profile decides what starts.

| Profile | Components | Memory | Needed from |
|---|---|---|---|
| `core` | Kafka, PostgreSQL, Redis | ~1.1 GiB | build steps 1–5 |
| `obs` | Zipkin, Prometheus | ~0.5 GiB | build step 8 |
| `full` | all of the above **+ Elasticsearch** | ~3.1 GiB | build step 6 onward |

Set it in [`.env`](./.env), which is committed and holds no secrets:

```
COMPOSE_PROFILES=core
```

Override it per command without editing the file — this is how the `full` environment gets
validated without changing what everyone else runs:

```bash
COMPOSE_PROFILES=full make up
```

Profiles **combine as a union**, so `core,obs` gives five components (~1.6 GiB).

> **`full` is six components, not the seven listed in the project brief.** Eureka is missing on
> purpose. Unlike everything here it is not a third-party server with an image to pull — it is a
> Spring Boot application this project builds — so it arrives in build step 7 alongside the API
> gateway that is its only consumer, adding ~384 MiB then.

---

## Memory budget

Every component declares a hard cap enforced by the kernel through cgroups v2. The cap is not a
performance tuning knob; it is **failure isolation**. Without one, a container that grows without
bound drags the whole host into swap and every component degrades together, with nothing
indicating which one caused it. With one, that container is killed and everything else keeps
serving. A loud attributable failure beats a slow global one.

| Component | `mem_limit` | Internal tuning | Profiles |
|---|---|---|---|
| Kafka | 768 MiB | `KAFKA_HEAP_OPTS=-Xms512m -Xmx512m` | `core`, `full` |
| PostgreSQL | 256 MiB | `shared_buffers=128MB`, `max_connections=50` | `core`, `full` |
| Redis | 96 MiB | `--maxmemory 64mb --maxmemory-policy noeviction` | `core`, `full` |
| Elasticsearch | 1.5 GiB | `ES_JAVA_OPTS=-Xms640m -Xmx640m`, `xpack.ml.enabled=false` | `full` |
| Zipkin | 256 MiB | `-XX:MaxRAMPercentage=65` | `obs`, `full` |
| Prometheus | 256 MiB | `--storage.tsdb.retention.time=6h` | `obs`, `full` |
| **`core` total** | **1120 MiB** | | |
| **`full` total** | **3168 MiB** | | |

Each also sets `memswap_limit` equal to `mem_limit`, which forbids that container from swapping at
all. On a machine whose swap is already under pressure, a container that fails fast is more useful
than one that crawls.

**Have ~2 GiB genuinely free before `make up` under `core`**, and ~4 GiB under `full`. Check with:

```bash
free -h
ps -eo pid,rss,comm --sort=-rss | head -15    # what is holding the memory
```

### Why every JVM component pins its heap

Kafka, Elasticsearch, and Zipkin all run on the JVM, and all three set their heap size explicitly.
Since Java 10 the JVM reads its cgroup limit rather than host memory — but it then defaults the
heap to only **25%** of it. Left alone, Kafka would run a 192 MiB heap inside a 768 MiB container
and waste the rest.

The heap must also stay well *below* the limit, because metaspace, thread stacks, direct byte
buffers, and GC structures all live outside it. Roughly 60–70% is the working range.

**Elasticsearch sits lower still, at 42%, and that figure was learned the hard way.** It originally
had a 1 GiB cap with the same 640 MiB heap, and it was OOM-killed during startup — `OOMKilled=true`,
exit 137 — before serving a request. `-Xms` commits the whole heap immediately, and outside it sit
metaspace, thread stacks for the hundred-plus threads Elasticsearch starts, Netty's direct buffers,
and GC structures; together they cleared 1 GiB before Lucene cached a single segment.

Two things changed. The cap went to 1.5 GiB, and `xpack.ml.enabled=false` switched off the native
machine-learning processes, which allocate *outside* the JVM heap and are therefore invisible to
`-Xmx` while counting fully against the container cap.

The low ratio is right for a second reason too: Lucene memory-maps index segments and reads them
through the **OS page cache**, which is also outside the heap. A heap set near the limit starves
that cache and makes search slower, not faster.

---

## Disk footprint

Image download sizes, measured for `linux/amd64`:

| Component | Download | 
|---|---|
| Kafka | 461 MiB |
| Elasticsearch | 620 MiB |
| Zipkin | 148 MiB |
| PostgreSQL | 111 MiB |
| Prometheus | 102 MiB |
| Redis | 16 MiB |
| **`core`** | **~590 MiB** |
| **`full`** | **~1.45 GiB** |

Images expand roughly twofold on disk, so budget **~1.5 GiB for `core`** and **~3 GiB for `full`**,
plus a few hundred MiB of volume data. **Allow 5 GiB free** to be comfortable. Measure the real
figure on your machine once the environment has run at least once:

```bash
docker system df                     # images, containers, and volumes
docker system df -v                  # per image and per volume
```

The first `make up` is the slow one — expect 2–5 minutes under `full` while images pull. Later
starts use the local cache and take well under a minute.

---

## Port map

Every port below is published to `localhost`, so tools on the host connect at plain `localhost:PORT`.

| Component | Port | Used for |
|---|---|---|
| Kafka | 9092 | client bootstrap from the host |
| PostgreSQL | 5432 | `psql`, and the services' datasource |
| Redis | 6379 | `redis-cli`, seat locks |
| Elasticsearch | 9200 | REST API, the read model |
| Zipkin | 9411 | trace UI and collector |
| Prometheus | 9090 | metrics UI |
| *Eureka* | *8761* | *not yet — arrives in build step 7* |

Two Kafka ports are **not** published, deliberately: `29092` is the inter-container listener and
`9093` is the KRaft controller. Nothing outside the Compose network needs either.

> Kafka advertises two listeners because a client does not keep talking to the address it
> bootstrapped against — the broker replies with its *advertised* address and the client reconnects
> there. Containers use `kafka:29092`; processes on the host use `localhost:9092`. Same broker, two
> doors.

---

## When something goes wrong

### A component was killed — exit code 137

137 means the process received SIGKILL (128 + 9). By far the most common cause here is the kernel's
OOM killer enforcing a `mem_limit`. Confirm it rather than guessing:

```bash
docker inspect kafka --format '{{.State.OOMKilled}} {{.State.ExitCode}}'
```

`true 137` is conclusive: that container exceeded its cap. `false 137` means something else sent
the kill — usually you, or a `docker stop` that timed out.

If it was an OOM kill, in order of likelihood:

1. **Run a smaller profile.** If you are on `full` and only need steps 1–5, `core` frees 2 GiB.
2. **Free host memory.** The cap is what the container may use; the host still has to have it.
3. **Raise that one limit** in `docker-compose.yml` — and if the component is JVM-based, raise its
   heap setting with it. Raising `mem_limit` alone changes nothing for Kafka or Zipkin, whose heaps
   are pinned by their own environment variables.

Note that a hard `mem_limit` makes this failure *visible and attributable*. The same shortage
without limits shows up as the entire machine thrashing, which is much harder to trace back.

### A container exits immediately — port conflict

Something on the host already holds the port.

```bash
make logs SERVICE=postgres          # names the conflict
sudo lsof -i :5432                  # identifies what holds it
```

A local PostgreSQL or Redis installation is the usual culprit. Stop it, or change the host side of
the mapping in `docker-compose.yml` — `"5433:5432"` publishes to a different host port while the
container keeps its own.

### A component never becomes healthy

```bash
make health                                 # which one
make logs SERVICE=<name>                    # why
docker inspect <name> --format '{{json .State.Health}}' | python3 -m json.tool
```

The last command prints the health check's recent output, which is usually the actual error.

**Elasticsearch reporting `yellow` is correct, not a failure.** A single-node cluster assigns every
primary shard and then cannot place a single replica, because a replica on the same node would
protect against nothing. Yellow — "all data available, no redundancy" — is its healthy steady
state, and it never reaches green. The health check accepts `green|yellow` for exactly this reason;
insisting on green would hang startup forever while the logs showed a perfectly healthy cluster.

### Startup fails after a previous run — stale volume

Kafka stamps a cluster id into its data directory on first start and refuses to start against a
directory holding a different one. The symptom is a metadata mismatch that does not obviously say
"stale data".

```bash
make down     # removes volumes; this IS the fix
make up
```

`make down` passes `-v` precisely so this class of failure cannot accumulate.

---

## Files here

| File | Purpose |
|---|---|
| `docker-compose.yml` | all six component definitions, limits, and health checks |
| `kafka-init/create-topics.sh` | creates the fourteen message channels; run by `make up` |
| `.env` | `COMPOSE_PROFILES` — which components `make up` starts |
| `prometheus/prometheus.yml` | scrape configuration |
| `README.md` | this file |

Nothing in this directory is run directly. `make` at the repository root passes
`-f infra/docker-compose.yml`, and Compose resolves `.env` and the relative bind mounts from the
compose file's own location, so the commands work from anywhere in the repository.
