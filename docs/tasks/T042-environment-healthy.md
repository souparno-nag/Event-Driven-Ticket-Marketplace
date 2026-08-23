# T042 — Scenario 3: the environment comes up healthy (SC-001, SC-002)

**What this task did:** started the real environment under both profiles and checked every
component reported healthy. It passed — but only after fixing **two genuine defects** that this was
the first thing to expose. That is the whole reason a verification step is a task rather than a
formality.

| Run | Result |
|---|---|
| `core`, first ever start | ✅ 3/3 healthy — 7m34s, almost all of it image pulls |
| `full`, first attempt | ❌ **Elasticsearch OOM-killed**, exit 137 |
| `full`, after the fix | ✅ 6/6 healthy in **10.98s** |

---

## Part A — `core` passed immediately

```
✔ Container kafka     Healthy   6.3s
✔ Container postgres  Healthy   5.8s
✔ Container redis     Healthy   5.3s
```

The wall-clock figure was 7m34s, but that number is about the network, not the environment: it is
~590 MiB of images downloading once. The design's actual startup cost is the six seconds above.

All three report healthy within a second of each other because they start **in parallel** — the
practical payoff of T038's finding that none of them has an ordering dependency. Chained with
`depends_on`, the same three would have taken three times as long for no benefit.

---

## Defect 1 — Elasticsearch was OOM-killed

The first `full` startup failed:

```
⠸ Container elasticsearch   Waiting   9.4s
container elasticsearch exited (137)
make: *** [Makefile:48: up] Error 1
```

The diagnostic written into `infra/README.md` one task earlier answered it on the first try:

```bash
docker inspect elasticsearch --format '{{.State.OOMKilled}} {{.State.ExitCode}}'
true 137
```

`true` is conclusive — the kernel killed it for exceeding its `mem_limit`. Not a configuration
error, not a slow start, not a port conflict.

### Why 1 GiB was the wrong number

The budget looked reasonable: a 640 MiB heap inside a 1 GiB cap. The mistake was counting only the
heap. `-Xms640m` **commits** all 640 MiB immediately, and the JVM then needs, entirely outside it:

- metaspace, holding class metadata
- the JIT code cache
- a thread stack for each of the hundred-plus threads Elasticsearch starts
- Netty's direct byte buffers, used for every network transfer
- the garbage collector's own bookkeeping structures

Those clear 1 GiB together before Lucene caches a single index segment. The heap was **62.5% of the
cap**, where Elastic's own guidance is at most 50% — and lower inside a container, because the cap
is a hard ceiling rather than a machine that can swap.

### The fix, and the fix that was rejected

The cap went to **1.5 GiB**, putting the heap at 42%, and `xpack.ml.enabled=false` was added.

That second flag is the interesting one. Elasticsearch 8 runs machine learning as **native
processes outside the JVM**. Their memory is therefore invisible to `-Xmx` — no heap setting
constrains it — while counting fully against the container cap. It is exactly the kind of
consumption that makes heap arithmetic look correct while the container dies anyway. Nothing in
this project uses ML, so it is pure overhead removed from the tightest budget in the file.

The alternative was keeping the 1 GiB cap and cutting the heap to 512m. Rejected, and recorded as a
`TRADEOFF:` in the compose file: **off-heap demand is what overflowed**, so a smaller heap only buys
back the difference while making every search slower. Shrinking the part that was not the problem is
a way of appearing to fix something.

`full` now costs ~3.1 GiB rather than ~2.6 GiB. That is a real cost, paid in a profile nothing needs
until build step 6, and it is now the documented figure in the compose header, `.env`, and README.

---

## Defect 2 — `make down` did not tear everything down

After the failed `full` run, `make down` reported success:

```
✔ Container redis      Removed
✔ Container kafka      Removed
✔ Container postgres   Removed
! Network ticket-marketplace_default   Resource is still in use
```

Three containers removed, and a warning that reads like a minor complaint. In fact **Zipkin and
Prometheus were still running**, and stayed running for another forty minutes. The network could not
be removed because they were still attached to it. Running `make down` again did nothing, because
from its point of view there was nothing left to do.

### The cause

Compose filters `down` by the active profile exactly as it filters `up`. `COMPOSE_PROFILES` was back
to `core`, so `down` considered only Kafka, PostgreSQL, and Redis. The three `full`-only containers
were invisible to it — not orphaned, not failed, simply out of scope.

This is worse than it first appears. The containers holding memory were the ones deliberately *not*
being used, the command that should have reclaimed it reported success, and the only visible symptom
was a warning about a network.

### The fix

```makefile
down:
	$(COMPOSE) --profile '*' down -v --remove-orphans
```

`--profile '*'` enables every profile, so teardown always covers the whole file. The asymmetry with
`up` is deliberate and worth stating: **starting is a question of what you need; stopping never is.**
Teardown should mean "leave nothing behind" regardless of what the environment happened to be set to
when you started it. `--remove-orphans` extends the same principle to containers whose service has
since been deleted from the compose file.

Verified against the actual mess it was written for — the leftovers from the failed run:

```
✔ Container prometheus      Removed
✔ Container elasticsearch   Removed
✔ Container zipkin          Removed
✔ Volume  …elasticsearch-data   Removed
✔ Network ticket-marketplace_default   Removed
```

`docker ps -a` then returned empty.

---

## Part B — `full` after the fixes

```
✔ Container prometheus      Healthy    5.8s
✔ Container postgres        Healthy    5.8s
✔ Container kafka           Healthy    6.3s
✔ Container redis           Healthy    5.3s
✔ Container zipkin          Healthy    5.8s
✔ Container elasticsearch   Healthy   10.8s

real  0m10.980s
```

Six for six, and `make health` agreed:

```
  ok    elasticsearch    healthy
  ok    kafka            healthy
  ok    postgres         healthy
  ok    prometheus       healthy
  ok    redis            healthy
  ok    zipkin           healthy
```

Elasticsearch is the long pole at 10.8 seconds — a JVM start plus index recovery — and the other
five finish in about six. **Eleven seconds against a five-minute budget.**

Its health check accepts `green|yellow`, and yellow is what it reports: a single-node cluster
assigns every primary shard and then cannot place a replica, since a replica on the same node would
protect against nothing. Insisting on green would have hung this startup forever while the logs
showed a perfectly healthy cluster.

---

## What it demonstrates

- **SC-001**: a fully healthy environment from a single documented command, no manual steps. ✅
- **SC-002**: every component healthy within 5 minutes. ✅ — 11 seconds warm, 7m34s cold including
  the one-time download.
- **FR-016**: `make health` reported all six individually, deriving its list from the active
  profile. ✅ Running it under `full` listed six components; the same command under `core` lists
  three.

---

## The lesson worth keeping

Both defects were **invisible to every check that came before**. The compose file parsed. The
profiles resolved. The health checks were correctly written. `make health` worked. Every static
verification passed — and the environment still could not start, because a memory limit was
arithmetically wrong and a teardown command silently covered a third of what it claimed to.

Neither could have been found by reading the file more carefully. A limit is only wrong relative to
what a process actually allocates, and a teardown is only incomplete relative to what is actually
running. Some categories of correctness are only observable by running the thing.
