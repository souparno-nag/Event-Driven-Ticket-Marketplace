# T060 — The service's configuration file

**What this task did:** replaced the generated `application.properties` with an `application.yml`
carrying the database connection, the limits that make this service refuse work politely under
load, the relay's settings, and the health and tracing endpoints.

---

## Why YAML instead of properties

Initializr generates `application.properties`, which is a flat list of `key.sub.key=value` lines.
YAML nests, so related settings sit together:

```properties
spring.datasource.hikari.maximum-pool-size=20
spring.datasource.hikari.connection-timeout=250
```

```yaml
spring:
  datasource:
    hikari:
      maximum-pool-size: 20
      connection-timeout: 250
```

Both are read identically by Spring. The real reason for switching is comments: this file makes
several decisions that are not obvious, and YAML's indentation keeps a paragraph of explanation
attached to the setting it explains. Only one of the two formats is kept — having both is a
reliable way to spend an afternoon wondering why a setting has no effect.

---

## The two settings that matter most

These are the ones that make the service behave sensibly when it is overloaded.

### `maximum-pool-size: 20`

A **connection pool** is a small set of already-open database connections that requests borrow and
return. Opening a connection is slow, so reusing twenty of them beats opening one per request.

Twenty is also, deliberately, this service's **admission limit**. Every accepted booking needs a
connection to run its transaction, so the pool is the genuine bottleneck on the write path. Rather
than adding a separate rate limiter that could disagree with it, the pool is made the one thing
that decides how much work is in flight.

### `connection-timeout: 250`

When all twenty connections are busy, a new request waits here. The default is **thirty seconds**.

That default is what turns a traffic spike into a disaster. A thousand requests arrive, all queue,
all get slower together, and thirty seconds later they all fail at once — and every one of those
callers waited half a minute to be told nothing useful. Nobody was served well.

At 250 milliseconds, the service absorbs ordinary jitter and then gives up. Requests beyond
capacity are refused immediately with `503 Service Unavailable` and a "try again shortly" message,
while the requests that *were* admitted stay fast. A quick, honest refusal beats a slow failure.

### `default-timeout: 3s` on transactions

The partner to the above. It caps how long a single transaction may hold its connection. Without
it, one slow query keeps a connection out of the pool indefinitely, and the 250 ms admission limit
protects nothing — the pool just drains and stays drained.

---

## `ddl-auto: validate`

Hibernate, the library that maps Java objects to database tables, can create and alter tables
itself. This setting controls how much it is allowed to do:

| Value | Behaviour |
|---|---|
| `update` | Silently alters tables to match the code |
| `create-drop` | Deletes and rebuilds the schema on every start |
| `validate` | Looks, compares, and refuses to start if they disagree |
| `none` | Does not look at all |

**Flyway owns the schema here**, not Hibernate. Flyway applies numbered migration files in order
and records what it has run, so a fresh checkout and a long-running database converge on the same
structure. `validate` keeps Hibernate as a checker rather than a second, competing author.

The payoff: forget to write a migration for a new field and the application refuses to start with a
clear message naming the missing column. With `update` it would have quietly added the column, and
the mismatch between your machine and everyone else's would surface much later, somewhere else.

---

## Relay settings

```yaml
outbox:
  relay:
    poll-interval-ms: 500
    batch-size: 100
    max-attempts: 5
```

These are custom, not Spring's — the relay reads them by name. Keeping them here rather than
hard-coded is what lets a test slow the relay to a crawl to create a crash window, without editing
code.

`poll-interval-ms` is a fixed **delay**, measured from the end of one run to the start of the next,
not a fixed **rate**. With a fixed rate, a run that takes longer than the interval starts
overlapping itself.

---

## Tracing, and a warning you should expect

```yaml
management:
  tracing:
    sampling:
      probability: 1.0
  zipkin:
    tracing:
      endpoint: http://localhost:9411/api/v2/spans
```

**Sampling at 1.0 means every single request is traced.** That is right for a demonstration and
wrong for production, where tracing everything is expensive and a small percentage is enough.

Zipkin is the UI that displays traces. It lives in the `obs` Docker Compose profile, so with the
default `core` profile it is not running, and you will see the reporter log a warning that it
cannot reach `localhost:9411`.

**That warning is harmless.** Trace context is still created, and still written onto every outbox
row — which is what allows a message published minutes later to remain part of the original
request's trace. Only *viewing* traces needs Zipkin. Run `COMPOSE_PROFILES=core,obs` when you want
to look at them.

---

## Endpoints

```yaml
management:
  endpoints:
    web:
      exposure:
        include: health,info,prometheus
```

Actuator adds diagnostic HTTP endpoints. It offers a lot of them, and this list is deliberately
short: is it alive (`health`), what is it (`info`), and its metrics in the format Prometheus
collects (`prometheus`). An endpoint nobody uses is an endpoint nobody is watching.
