# T030 — PostgreSQL

**What this task did:** added the `postgres` service to `infra/docker-compose.yml`.

---

## What it is for

PostgreSQL is the **durable** side of this system. Nothing uses it yet; from build step 2 it holds:

- the `orders` table — the saga's authoritative state
- the `outbox` table — messages written in the same transaction as the order they describe
- `reservations` — inventory's durable record, promoted from a Redis hold at confirmation
- `processed_events` — the idempotency keys every consumer checks

The pairing with Redis in T031 is the interesting part of the design. Redis holds seat locks because
they are fast and expire on their own; PostgreSQL holds what must survive a restart. Neither could
do the other's job well.

---

## Configuration decisions

### The credentials are committed, and that is fine *here*

```yaml
POSTGRES_USER: marketplace
POSTGRES_PASSWORD: marketplace
```

Ordinarily a password in version control is a serious mistake. The reasoning that makes it
acceptable in this file:

- the database is published only to `localhost` on a developer machine,
- it holds nothing but generated demo data,
- and secrets management is explicitly out of scope for this project.

What matters is that the exception is *reasoned*, not habitual. A real deployment reads these from a
secret store, and the Helm chart in build step 10 skips secrets deliberately rather than by
oversight. Being able to say why a rule is being broken is the difference between a shortcut and a
bad habit.

### `shared_buffers=128MB` inside a 256 MiB limit

```yaml
- -c
- shared_buffers=128MB
```

`shared_buffers` is PostgreSQL's own page cache — memory it reserves up front to hold table and
index pages, so repeated reads do not go to disk.

It is set explicitly because the container limit is 256 MiB and this is allocated immediately.
Half the container for the cache, half for everything else (backend processes, work memory,
connection overhead) is a reasonable split at this size. The general guidance of "25% of system
RAM" assumes a dedicated database server with gigabytes to play with; inside a small container the
calculation is about what is left over.

### `max_connections=50`, lower than the default 100

This one is worth understanding, because PostgreSQL differs from most databases here.

**PostgreSQL forks a separate OS process for every connection.** Not a thread — a process, with its
own memory for sorting, hashing, and temporary results. So a connection limit *is* a memory limit,
and 100 backends under a 256 MiB cap is a route to exit code 137.

Fifty is far more than this project will use: three services with a default HikariCP pool of ten
each is thirty. And the 1000 virtual users in the step-9 load test hit the API gateway, not the
database — they queue on the connection pool, which is exactly what a pool is for.

That last point is the general lesson: **connection pools exist because connections are expensive.**
Applications hold a small pool and share it across many requests, rather than opening one per
request. A load test that overwhelms your database usually means a pool is misconfigured, not that
the database is too small.

---

## `pg_isready` is a readiness probe, and the distinction matters here

```yaml
test: ["CMD-SHELL", "pg_isready -U marketplace -d marketplace"]
```

PostgreSQL's startup has a phase most people never notice: the process starts, **runs crash
recovery**, and only then begins accepting connections. On a container that was killed rather than
shut down cleanly — which `docker compose down` without `-v` can produce — that recovery is real
work.

A port check passes the moment the process binds, *during* recovery. Anything that started on the
strength of that signal fails to connect, with an error that looks like a bug in the client.
`pg_isready` asks the server the question that actually matters: *are you accepting connections?*

This is **FR-012** — readiness, not liveness — and it is why `depends_on: condition: service_healthy`
in T038 will be trustworthy.

---

## Try it yourself

Syntax and resolved values check out (parse only, nothing started):

```bash
docker compose -f infra/docker-compose.yml --profile core config
```

`mem_limit` resolves to `268435456` — exactly 256 MiB.

Starting it is yours:

```bash
docker compose -f infra/docker-compose.yml --profile core up -d postgres
docker compose -f infra/docker-compose.yml ps
```

**Expect**: `postgres` reaching `Up (healthy)` in a few seconds.

Then connect and confirm the settings took effect:

```bash
docker exec -it postgres psql -U marketplace -d marketplace -c "SHOW shared_buffers; SHOW max_connections;"
```

**Expect**: `128MB` and `50`. Worth checking rather than assuming — a mistyped `-c` flag is silently
ignored by some images and fatal in others.

To watch the readiness probe do its job, restart it and poll:

```bash
docker compose -f infra/docker-compose.yml --profile core restart postgres
docker inspect --format '{{.State.Health.Status}}' postgres
```

**Expect**: `starting`, then `healthy`. That gap is the window where a port check would have lied.

---

## What comes next

**T031** — Redis, whose eviction policy is the single most consequential line in this whole Compose
file. Set it wrong and the system double-books seats under memory pressure, silently.
