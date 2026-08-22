# T034 — Zipkin, distributed tracing

**What this task did:** added the `zipkin` service, tagged into the `obs` and `full` profiles.

---

## The problem tracing solves

One booking touches five services. When it goes wrong, the evidence is scattered:

```
order-service       12:04:31.221  received order 8f3a...
inventory-service   12:04:31.240  locking seats
payment-service     12:04:31.980  charging
order-service       12:04:32.104  confirmed
```

Four log files, four clocks, and no thread tying them together. Reconstructing one order's journey
means grepping the same id across every service and hoping the timestamps are comparable — which,
as T020 noted about clock drift, they may not be.

**Distributed tracing** replaces that with a single record. Each request gets a **trace id** at the
edge; every service passes it along, and each unit of work becomes a **span** with a parent. The
result is a tree:

```
trace 8f3a...  ────────────────────────────────────────  873ms
├── order-service      POST /api/orders                    31ms
├── inventory-service  lock seats (Redis Lua)               19ms
├── payment-service    charge                              742ms   ← there it is
└── order-service      confirm                              12ms
```

You do not read that, you *look* at it. Which step was slow, which failed, and how they nest are all
visible at a glance. This is the payoff for a choreographed saga, where no single service knows the
whole flow.

Micrometer Tracing in each service produces the spans; Zipkin collects them and draws the picture.
That wiring arrives with the services; this task just makes the collector exist.

---

## Where the trace id travels — and where it must not

Worth connecting to a contract decision. **FR-024** says trace correlation data travels in broker
message **headers**, never in the message body.

That is why none of the seven records has a `traceId` component. The seven contracts stay limited to
business facts, so changing how the system is observed — swapping Zipkin for something else,
changing the propagation format — touches no contract and needs no `schemaVersion` bump.

A useful separation in general: **what happened** goes in the payload; **how we are watching** goes
in the envelope around it.

---

## `MaxRAMPercentage` instead of a fixed heap

```yaml
JAVA_TOOL_OPTIONS: -XX:MaxRAMPercentage=65
```

Kafka got `-Xms512m -Xmx512m`; this uses a percentage. Both are valid, and the difference is worth
knowing.

`MaxRAMPercentage` tells the JVM to size its heap as a fraction of the memory it can see — which,
since Java 10, is the **cgroup limit**, not host RAM. So the setting stays correct if `mem_limit`
changes later, where a fixed `-Xmx` would silently become wrong.

But it must be set explicitly, because the default is **25%**. A 256 MiB container with no setting
runs a 64 MiB heap and wastes the rest. This is the same trap as T029's Kafka heap, wearing a
different hat: in both cases the JVM correctly detects its limit and then makes a conservative
choice nobody wanted.

Why not use percentages everywhere? For Kafka the exact heap matters enough to state it outright.
For a supporting component like Zipkin, "about two thirds of whatever it gets" is the actual
intention, and saying so directly is clearer than a number that has to be recomputed by hand.

---

## In-memory storage, deliberately

```yaml
STORAGE_TYPE: mem
```

Zipkin can persist traces to Elasticsearch or Cassandra. Here they live in memory and vanish on
restart.

That follows from what traces *are*: diagnostic breadcrumbs, not records. You look at a trace
minutes after something went wrong, not months later during an audit. Persisting them would mean
running a storage backend, giving it memory, and keeping data nobody reads twice.

Same reasoning as Redis's disabled snapshotting in T031 — **match durability to what the data
means** — reached independently for a completely different kind of data. A pattern worth
recognising: not everything in a system needs to survive a restart, and paying for durability you do
not need has a real cost.

---

## `wget` rather than `curl` in the health check

```yaml
test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:9411/health"]
```

Elasticsearch's probe in T032 used `curl`. This one uses `wget`, and the difference is not
stylistic: **a health check can only run binaries that exist inside that image.**

Container images are minimal on purpose. The Elasticsearch image ships `curl`; this one is
Alpine-based, so it has busybox `wget` and no `curl` at all. A `curl` probe here would fail with
`executable file not found`, and the container would sit permanently unhealthy for reasons that have
nothing to do with Zipkin.

`--spider` fetches headers without downloading the body, which is all a probe needs.

The habit to take away: when writing a health check, confirm the tool is actually in the image. It
is one of the more common causes of a "broken" container that is running perfectly.

---

## The `obs` profile

```yaml
profiles: ["obs", "full"]
```

Zipkin is the first service in `obs` — observability, needed at build step 8. Combined with
Prometheus in T035 that profile is ~0.5 GiB, so a developer working on tracing can run
`COMPOSE_PROFILES=core,obs` and skip Elasticsearch and Eureka entirely.

Profiles compose: listing two activates the union of both.

---

## Try it yourself

```bash
docker compose -f infra/docker-compose.yml --profile obs config --services   # zipkin
docker compose -f infra/docker-compose.yml --profile obs up -d zipkin
```

**Expect**: healthy within about 20 seconds.

The UI is the point of it, so open it:

```
http://localhost:9411
```

**Expect**: an empty search page. Nothing is producing traces until the services exist in step 2 —
this task only puts the collector in place.

You can prove the collector works by posting a trace by hand:

```bash
curl -s -X POST http://localhost:9411/api/v2/spans -H 'Content-Type: application/json' -d '[{
  "traceId":"aaaaaaaaaaaaaaaa","id":"bbbbbbbbbbbbbbbb","name":"fake-booking",
  "timestamp":'$(date +%s000000)',"duration":150000,
  "localEndpoint":{"serviceName":"order-service"}
}]'
```

Then reload the UI and run a search. **Expect**: one trace named `fake-booking` from
`order-service`, lasting 150ms. That is exactly the shape Micrometer will produce automatically once
the services are wired up.

---

## What comes next

**T035** — Prometheus, the metrics collector, and the one service that needs a special escape hatch to
reach applications running on your host rather than in a container.
