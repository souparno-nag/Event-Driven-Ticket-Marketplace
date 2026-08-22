# T032 — Elasticsearch, and why `yellow` means healthy

**What this task did:** added the `elasticsearch` service, tagged `full` only. Its health check
contains the most counter-intuitive line in the file.

---

## What it is for: the read model

Elasticsearch holds the **CQRS read model**. From build step 6, projection-service consumes saga
messages and maintains one document per show:

```json
{ "showId": "...", "totalSeats": 120, "availableSeats": 118,
  "seatStatusMap": { "A12": "SOLD", "A13": "SOLD", "A14": "FREE" } }
```

`GET /api/availability/{showId}` reads **only** from there — it never touches PostgreSQL or Redis.

**CQRS** is Command Query Responsibility Segregation: the store you write to and the store you read
from are different, kept in step by events. What it buys is a read model shaped for the query you
actually serve — one document, one lookup, instead of joining orders to reservations to seats on
every page load — and read traffic that scales without touching the write path.

What it costs is the consistency window T024 described: for a few milliseconds after a confirmation,
the read model still shows the old answer. Which is fine for rendering a seat map and never
acceptable for deciding whether a seat can be sold. That decision belongs to the Redis lock, which
is the authority.

---

## The health check accepts `yellow`

```yaml
test: ["CMD-SHELL", "curl -sf http://localhost:9200/_cluster/health | grep -qE '\"status\":\"(green|yellow)\"'"]
```

Every instinct says green is healthy and yellow is a warning. Here, **yellow is the correct steady
state and green is unreachable.**

Elasticsearch reports cluster health as a colour:

| Colour | Meaning |
|---|---|
| **green** | Every primary shard assigned, **and every replica assigned** |
| **yellow** | Every primary assigned, one or more replicas unassigned |
| **red** | At least one primary shard unassigned — data is missing |

A **shard** is a piece of an index; a **replica** is a copy of a shard kept on a *different node* so
the data survives that node dying.

On a single-node cluster, a replica would have to live on the same node as its primary — where it
would protect against nothing. Elasticsearch declines to place it. So every index has unassigned
replicas, permanently, and the cluster sits at yellow forever by design.

Insisting on green produces a beautiful failure:

```
$ docker compose up
[waiting...]                     ← forever
$ docker compose logs elasticsearch
[INFO] Cluster health status changed to [YELLOW]     ← it is fine, and has been for ten minutes
```

The container never reports healthy, everything with `depends_on: service_healthy` never starts, and
the logs show a perfectly working cluster. R8 calls this out explicitly as a single-node trap, which
is why the probe uses a regex matching either colour.

Note what is *not* accepted: **red**. Red means a primary shard is missing, which is genuine data
unavailability. The check draws the line in the right place rather than accepting anything
non-fatal.

---

## Heap at 640 MiB inside a 1 GiB limit

```yaml
ES_JAVA_OPTS: -Xms640m -Xmx640m
```

Every JVM component in this file leaves headroom outside the heap. Elasticsearch is the one where
that headroom does *work* rather than merely existing.

Lucene, the search library underneath, **memory-maps** its index files and reads them through the
operating system's page cache. That cache is outside the JVM heap entirely.

So the trade is unusual:

```
container limit  1 GiB
├── JVM heap         640 MiB   query execution, aggregations, indexing buffers
└── everything else  384 MiB   ← Lucene's mapped segments live in here
```

Giving Elasticsearch a bigger heap makes it **slower**, because it starves the file cache and pushes
index reads back to disk. The official guidance is roughly half of available memory for the heap,
never above ~31 GiB — and the reason for the upper bound is a fun one: above about 32 GiB the JVM
loses compressed object pointers and effectively wastes memory to address more of it.

A rare case where "give it more memory" is the wrong instinct.

---

## Security off, single-node discovery

```yaml
discovery.type: single-node
xpack.security.enabled: "false"
```

`single-node` skips the bootstrap checks a production cluster runs and stops the node looking for
peers that will never appear.

Security off avoids generating certificates and managing a password before the first request. Same
reasoning as PostgreSQL's committed credentials in T030: localhost-only, demo data, out of scope by
decision rather than by oversight. Elasticsearch 8 enables TLS and auth by default — turning it off
is a deliberate step, which is the right default for the product to have.

---

## `full` only — this is what profiles are for

```yaml
profiles: ["full"]
```

Elasticsearch is the heaviest component here at 1 GiB, and nothing uses it until build step 6.
Under `core`, it simply does not exist:

```
--profile core   →  kafka postgres redis
--profile full   →  elasticsearch kafka postgres redis
```

That is ~1.1 GiB versus ~2.1 GiB, for work that does not need it. On a machine where memory is
tight, this one tag is the difference between the environment being pleasant and being a
negotiation.

It also carries the consequence T040 has to respect: `make health` must derive its list from the
active profile. A hardcoded list of seven would report Elasticsearch as failing under `core`, when
in fact it was never asked to run.

---

## Try it yourself

Verified the profile gating (a parse-only check):

```bash
docker compose -f infra/docker-compose.yml --profile core config --services   # kafka postgres redis
docker compose -f infra/docker-compose.yml --profile full config --services   # + elasticsearch
```

Starting it is yours — and expect this one to be slow, 60 seconds or more on a first run:

```bash
docker compose -f infra/docker-compose.yml --profile full up -d elasticsearch
watch docker inspect --format '{{.State.Health.Status}}' elasticsearch
```

Then see the colour for yourself:

```bash
curl -s localhost:9200/_cluster/health | python3 -m json.tool
```

**Expect**: `"status": "yellow"` and `"unassigned_shards": 0` on a fresh cluster with no indices.
Create an index with a replica and watch it turn:

```bash
curl -s -X PUT localhost:9200/demo -H 'Content-Type: application/json' \
  -d '{"settings":{"number_of_shards":1,"number_of_replicas":1}}'
curl -s localhost:9200/_cluster/health | python3 -m json.tool
```

**Expect**: still `"status": "yellow"`, now with `"unassigned_shards": 1`. That is the replica
Elasticsearch refuses to place on the only node it has — the exact condition that makes green
impossible and this health check necessary.

```bash
curl -s -X DELETE localhost:9200/demo
```

---

## What comes next

**T033** — Eureka, the service registry. It is the one component with no official image, which makes
it the one choice in this file I could not fully verify.
