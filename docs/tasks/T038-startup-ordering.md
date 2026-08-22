# T038 — Startup ordering, and the edges that should not exist

**What this task did:** analysed which components must wait for which before starting, concluded
that **none of the six currently must**, and recorded that conclusion — along with the rule that
constrains the one real dependency arriving in T045 — in `infra/docker-compose.yml`.

No `depends_on` was added. That is the deliverable, and the rest of this document explains why
that is a result rather than a skipped task.

---

## The problem `depends_on` exists to solve

Start several containers at once and they all begin booting simultaneously. If one of them needs
another to be working — say a service that must write to a database — it may well make its first
request before the database is ready, get a connection refused, and crash.

Docker Compose's answer is `depends_on`:

```yaml
some-service:
  depends_on:
    postgres:
      condition: service_healthy
```

"Do not start `some-service` until `postgres` reports healthy." The `condition: service_healthy`
part is what makes this useful, and it is why T029–T035 spent so much care on health checks. The
weaker default, `condition: service_started`, only waits for the container to be *created*, which
tells you nothing — PostgreSQL's container exists for several seconds before PostgreSQL accepts
connections. A dependency gate is only as good as the health check it reads, which is exactly why
FR-012 insists those checks measure **readiness** ("can I serve a request?") rather than
**liveness** ("is the process alive?").

So the mechanism is sound. The question T038 asks is: where in *this* environment does it apply?

---

## Working through all six

This is the whole analysis, component by component.

**Kafka, PostgreSQL, Redis, Elasticsearch.** Each is a standalone data store. None of them reads
from, writes to, or registers with any of the others. Kafka in KRaft mode is specifically notable:
the classic reason a Kafka container needed `depends_on` was ZooKeeper, and FR-014 removed
ZooKeeper entirely (T029). The dependency that used to be the textbook example here no longer
exists.

**Zipkin.** This one is a near miss, and worth dwelling on. Zipkin stores traces, and it can store
them in Elasticsearch. If it did, it would genuinely need to wait for Elasticsearch to be healthy.
It does not, because T034 set `STORAGE_TYPE: mem` — traces live in Zipkin's own memory. So the
dependency is absent *because of a configuration choice made earlier*, not because Zipkin is
inherently independent. Change that one environment variable and this analysis changes with it.

**Prometheus.** The most interesting case, because at a glance it looks like it obviously depends
on everything: it scrapes metrics from the other services, so surely it needs them up first?

No — and this is a genuinely useful thing to understand about monitoring systems. Prometheus uses
a **pull** model: it reaches out to each target on a schedule and asks for metrics. A target that
does not answer is recorded as `up = 0` and retried at the next interval. That is not an error
condition; it is Prometheus doing its job. A monitoring system whose entire purpose is to notice
when things are down cannot itself refuse to start because something is down. Making Prometheus
wait for its targets would break the tool in the exact situation it was built for.

**Result: zero edges among the six.**

---

## Why the absence gets written down

An empty `depends_on` section and a *deliberately* empty one look identical in a file. Six months
later — or in an interview — "there is no ordering here" and "someone forgot the ordering" are
indistinguishable unless one of them is stated.

So `docker-compose.yml` now carries a `STARTUP ORDERING (FR-013)` block listing each component and
why it stands alone. Recording a decision not to do something is worth as much as recording a
decision to do it, and usually more, because nothing else in the file hints that the question was
ever asked.

---

## The rule that came out of the analysis

Before concluding "add no edges", it is worth knowing what an edge would actually cost. That got
tested rather than assumed, using a throwaway two-service compose file:

```yaml
services:
  base:
    profiles: ["core"]
  dependent:
    profiles: ["extra"]
    depends_on:
      base:
        condition: service_started
```

Starting only the `extra` profile — so `dependent` is active but `base` is not — gives:

```
service "dependent" depends on undefined service "base": invalid compose project
```

Read that carefully. Compose did not skip the dependency, and did not helpfully switch on the
`core` profile to satisfy it. It declared the **entire project invalid**. Nothing starts. Not the
dependent, not the unrelated services — nothing.

Further probing pinned the rule down:

| Active profiles | Result |
|---|---|
| `extra` only (dependent on, dependency off) | **whole project rejected** |
| `core` only (dependent off) | fine — an inactive service's edges are not checked |
| neither | fine |
| `core,extra` (both on) | fine |

**The rule: every dependency's profile list must be a superset of its dependent's.** An edge
between two services silently welds their profiles together, and the failure when you get it wrong
is not a warning about that one service — it is a total refusal with an error message naming a
component you were not thinking about.

The Zipkin example makes it concrete. Zipkin is tagged `obs, full`; Elasticsearch is `full` only.
Switch Zipkin to Elasticsearch storage, add the honest `depends_on`, and `COMPOSE_PROFILES=obs`
stops working entirely — with an error about Elasticsearch, which is not even in that profile.
The cause and the symptom are nowhere near each other.

This is also the check that confirms T045 will be safe: the topic provisioner is tagged
`core, full`, exactly matching Kafka, so its edge couples nothing that was not already coupled.

*(Verified against Docker Compose v2.39.1. This is documented behaviour, not a bug, but it is the
kind of behaviour that is much cheaper to discover in a scratch file than in a real one.)*

---

## The tempting wrong use, and why it was rejected

Under the `full` profile, six containers boot at once, and the peak memory demand during startup —
JVMs sizing heaps, Elasticsearch recovering indices — is the highest the environment ever sees.
Chaining `depends_on` across all six would stagger that, flattening the spike.

It is rejected, and the compose file records it as a `TRADEOFF:` for the reason rather than just
the conclusion:

- It **trades a memory problem for a latency problem.** Every startup becomes the sum of six
  startups instead of the longest one. Under `full`, where Elasticsearch alone allows 60 seconds
  of `start_period`, that is a materially slower `make up` every single time — to solve a problem
  that only occurs on constrained machines.
- The controls for memory are **already in place and are the honest ones**: `mem_limit`,
  `mem_reservation`, and `memswap_limit` per container (R10), plus profiles so you are not running
  what you do not need (R11).
- Worst of all, it **destroys the file's information value.** Once the graph contains edges that
  are not real dependencies, no reader can trust that any edge is a real dependency. A diagram that
  encodes two different things at once encodes neither.

Using a feature for a side effect it happens to produce is a familiar trap. It works, and it leaves
behind a file that lies about the system.

---

## So what *does* make startup wait?

`docker compose up -d` returns as soon as containers are created — not when they are healthy. With
no `depends_on` edges, nothing gates on health at all, and `make up && make health` (Scenario 3)
would report unhealthy components purely because they had not finished booting yet.

The fix belongs at the command, not in the graph: `docker compose up -d --wait` blocks until every
started container reports healthy, reading the same health checks `depends_on` would have. One flag
covers all six, applies no matter which profile is active, and encodes no false claims about
dependencies between them.

That flag lands in the `up` target in **T039**.

---

## In one line

`depends_on` expresses "A cannot work until B is ready". Among six independent backing stores
nothing meets that description, so nothing was added — but the analysis, the profile-superset rule
it uncovered, and the staggered-startup idea that was rejected are all now written where the next
person will find them.
