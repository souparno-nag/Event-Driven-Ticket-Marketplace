# T141 — Verifying Flyway and Hibernate against the real, shared environment

**What this task did:** verified SC-012 directly, not by inference from the Testcontainers-based
checks that came before it. Every prior task in this build step proved its own piece against a real
PostgreSQL database — but always an ephemeral, single-purpose Testcontainers instance, started fresh
and thrown away. This task asks a narrower, more concrete question: does a clean checkout, run with
nothing but the documented startup command against the *actual shared* development database this
project's `make up` provides, reach a working state with zero manual steps — and, critically, without
disturbing `order-service`, which already lives in that same database?

This is a verification task, not a code task — there is no new file to review, only evidence.

---

## Why the answer wasn't already known from everything that came before

Every temporary smoke test in T125 through T140 proved something real, but all of them proved it
against a database that started empty and disappeared afterward. That's the right tool for proving a
query or an entity mapping is *correct*, but it can't answer the specific question SC-012 asks:
what happens when this service's migrations run in a database `order-service` has *already* been
using? Two services' migrations landing in the same PostgreSQL instance for the first time is exactly
the scenario T117's own schema-isolation design (R12) exists to get right, and the only way to
actually test it is to put both services in the same real, running database at once.

Which meant discovering, honestly, that this had never actually happened yet in this session:
`order-service`'s own test suite uses its own Testcontainers-based `PostgresIT`, not the shared
`docker-compose` Postgres — so its migrations had never once run against the database
`infra/docker-compose.yml` provisions, in this session. That gap had to be closed before the
verification could mean anything.

---

## What was actually done, in order

**1. Started `order-service` for real**, against the real shared Postgres
(`./mvnw -pl order-service spring-boot:run`), and confirmed its own health check reported the
database up. This is what first created `public.flyway_schema_history` in that database — two
migrations, `create orders` and `create outbox` — establishing a genuine baseline rather than an
assumed one.

**2. Started `inventory-service` for real, alongside it** — both processes running simultaneously
against the same PostgreSQL instance — using the exact command quickstart.md documents:
`./mvnw -pl inventory-service spring-boot:run`. Its own health check came back:

```json
{"status":"UP","components":{"db":{"status":"UP", ...},"redis":{"status":"UP", ...}}}
```

`ddl-auto: validate` raised nothing. Had any entity's mapping disagreed with the real schema — a
missing table, a column type or length that didn't match — the application would have failed to start
at all, not reported a warning. A clean health check *is* the proof here, not merely suggestive of it.

**3. Confirmed both services' migration histories side by side, in the same live database:**

```text
public schema (order-service, untouched by inventory-service's startup):
 version |  description  | success
 1       | create orders | t
 2       | create outbox | t

inventory schema (created fresh by this startup, with zero manual steps):
 version |         description          | success
         | << Flyway Schema Creation >> | t
 1       | create seating plan          | t
 2       | create reservations          | t
 3       | create processed messages    | t
 4       | create outbox                | t
```

`order-service`'s own two migrations are exactly what they were before `inventory-service` ever
started — the same count, the same versions, the same success flags. Its own tables
(`orders`, `order_seats`, `outbox`, and its own `flyway_schema_history`) remain the only four
relations in `public`. Nothing about a second service's Flyway run touched any of it, which is the
concrete, observed answer to "does this disturb order-service's migration history" — not an inference
from reading `application.yml`'s schema settings, but the actual database state after both services
had genuinely run.

**4. Confirmed the seating plan was populated with zero manual steps** — `SELECT name FROM
inventory.shows` returned "Load Test Hall" and "Second Stage" the moment the application finished
starting, with no `psql` command run in between. This is SC-012's own language, verified literally: a
clean checkout reaches a working service with a correct schema and a populated seating plan using only
the documented startup command.

**5. Stopped both processes and reset the shared database to a clean baseline** — dropping the
`inventory` schema and `order-service`'s own tables — so this verification leaves no residue for
whichever task runs against this shared environment next, and re-ran the full multi-module
`./mvnw verify` to confirm nothing in either service's own automated test suite regressed as a result
of any of this.

---

## What this closes out

Phase 2 — Foundational — is now complete. Every table, every entity, a fully working second outbox,
every piece of infrastructure configuration, and the shared test infrastructure all exist, and this
task is the one that finally asked the "does it work for real, alongside the service that already
exists" question directly, rather than leaving it as an inference from smaller pieces each proven in
isolation. Phase 3 — User Story 1, seats held for exactly one order even under contention — can now
begin.
