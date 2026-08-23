# T041 — `infra/README.md`, the document that answers "will this run on my machine?"

**What this task did:** wrote `infra/README.md` — the operator's guide to the local environment. It
covers the profile table, the per-component memory budget, the disk footprint, the port map, and a
diagnostics section led by exit code 137.

This is a different kind of document from the ones in `docs/tasks/`. Those explain *why a decision
was made*, for someone learning. This one answers *what do I do right now*, for someone whose
environment just failed at 2am. Both matter; mixing them serves neither.

---

## Why a requirement exists for stating the footprint

FR-017 asks for the minimum memory and disk the environment needs. That can look like paperwork
until you consider what its absence costs.

Without it, a developer clones the repository, runs `make up`, and their machine begins swapping.
What they see is not "you need more memory" — it is Kafka dying with a number, or everything
becoming slow at once with no component obviously at fault. They now debug a **resource** problem
as though it were a **configuration** problem, which can burn an afternoon, because every log line
they read is genuinely about something else.

A single sentence — *`core` needs about 1.1 GiB, `full` about 2.6 GiB* — converts that afternoon
into a decision made in ten seconds, before anything is started.

The disk half matters for the same reason in a different disguise. A failed image pull on a full
disk produces an error about a layer, not about capacity.

---

## Every number was checked, not transcribed

The memory figures already appear in `research.md`. Copying them would have been faster, and would
have been wrong as a method: a specification records what was *intended*, and this README describes
what the compose file *actually does*. Those drift, and a document that quietly disagrees with the
system is worse than no document, because it is believed.

So the table was generated from the resolved Compose configuration:

```bash
COMPOSE_PROFILES=full docker compose -f infra/docker-compose.yml config --format json
```

`config` is Compose's own answer after merging the file, the `.env`, and the active profile — the
same computation `make up` performs. Every claim checked out:

| Claim in README | Verified |
|---|---|
| Kafka 768 / PostgreSQL 256 / Redis 96 MiB | ✅ |
| Elasticsearch 1 GiB / Zipkin 256 / Prometheus 256 MiB | ✅ |
| `memswap_limit` equals `mem_limit` everywhere | ✅ all six |
| `core` ≈ 1.1 GiB, `obs` ≈ 0.5 GiB, `full` ≈ 2.6 GiB | ✅ 1120 / 512 / 2656 MiB |
| Ports 9092, 5432, 6379, 9200, 9411, 9090 | ✅ |
| Kafka's 29092 and 9093 are **not** published | ✅ only 9092 appears |

That last one is the kind of detail a transcribed document gets wrong. Kafka's compose entry
mentions three listeners, and only one is published to the host.

---

## Download size is not disk size

No images had been pulled yet, so the disk figures came from the registry:

```
Kafka          461 MiB        Elasticsearch  620 MiB
Zipkin         148 MiB        PostgreSQL     111 MiB
Prometheus     102 MiB        Redis           16 MiB
```

These are **compressed** sizes, for `linux/amd64` specifically — a multi-architecture tag holds a
different image per platform, and quoting the wrong one is a real way to be confidently incorrect.

Container images ship as compressed layers and are **decompressed on disk**, so the space consumed
is roughly double what is downloaded. The README states both, labels which is which, and — rather
than presenting an estimate as a measurement — points at the command that gives the true local
answer once the environment has run:

```bash
docker system df -v
```

Admitting an estimate is an estimate is what makes the rest of the document trustworthy.

---

## Exit code 137, the diagnostic the task called for

This gets its own section because it is the failure this environment is most likely to produce, and
its error message says nothing useful.

**What 137 means.** Unix processes killed by a signal report `128 + signal number`. Signal 9 is
`SIGKILL`, the one that cannot be caught or ignored. So 137 means *something killed this process
outright*. The container simply vanishes.

**Why it happens here.** Every component declares a `mem_limit`, enforced by the kernel through
cgroups. When a container exceeds its cap, the kernel's OOM killer terminates it — no warning to
the process, no chance to log anything, which is exactly why the logs are unhelpful.

**How to confirm it rather than assume it:**

```bash
docker inspect kafka --format '{{.State.OOMKilled}} {{.State.ExitCode}}'
```

- `true 137` — conclusive, the cap was exceeded.
- `false 137` — something else sent the kill: a `docker stop` that timed out, or a person.

That distinction is the whole value of the command. Both cases look identical in `docker ps`, and
they have completely different fixes.

**Why the limits are there at all**, given they cause this: a cap converts a *global* failure into a
*local, attributable* one. Without limits, a runaway container drags the entire host into swap and
everything degrades together, with nothing indicating the cause. With limits, one container dies
with a traceable code and the other five keep serving. A loud failure that names itself beats a
slow one that does not.

The README's fixes are ordered by likelihood rather than by cleverness — switch to a smaller
profile, free host memory, then raise the limit — with one warning attached to the third: for
Kafka, Elasticsearch, and Zipkin, **raising `mem_limit` alone does nothing**, because their heaps
are pinned by their own environment variables and will not grow to use the extra room.

---

## The three "this is correct, not broken" notes

A good diagnostics section spends as much space on alarming behaviour that is fine as on behaviour
that is not, because false alarms cost real debugging time.

**Elasticsearch reports `yellow`.** A single-node cluster places every primary shard, then cannot
place a single replica — a replica on the same node would protect against nothing. Yellow is its
healthy steady state and it will never reach green. Demanding green hangs startup forever while the
logs show a perfectly healthy cluster.

**Kafka publishes only one of its three ports.** `29092` (inter-container) and `9093` (the KRaft
controller) stay inside the Compose network, because nothing outside needs them. The README
explains the advertised-listener mechanism behind this, since "I can see the port is open but my
client still cannot connect" is the single most common Kafka-in-Docker confusion.

**A stale volume breaks startup after a previous run.** Kafka stamps a cluster id into its data
directory and refuses to start against a directory holding a different one. The error is a metadata
mismatch that never mentions stale data. `make down` — which passes `-v` — *is* the fix, and the
README says so directly rather than making the reader connect the two facts under pressure.

---

## In one line

`infra/README.md` states what the environment costs before you start it and what to do when it
fails, with every number checked against the compose file that actually runs rather than copied
from the specification that describes it.
