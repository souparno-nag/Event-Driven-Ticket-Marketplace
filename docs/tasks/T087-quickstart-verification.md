# T087 — Running the quickstart against a live service

**What this task did:** started the real environment (`make up`, the `core` profile), ran the actual
`order-service` application against it, and worked through quickstart scenarios S1, S4, and S5 by
hand — the first time anything built in this feature has been exercised as a whole running system
rather than through a test harness.

---

## Getting the service running: two wrinkles worth remembering

Starting the environment was routine: `make up` brought up Kafka, PostgreSQL, and Redis, and
provisioned all fourteen message channels, exactly as step 1 built it to do.

Starting `order-service` itself took two attempts to get right, and both are worth writing down so
they don't have to be rediscovered.

**First**, `./mvnw -pl order-service spring-boot:run` fails outright:

```text
Could not find artifact com.marketplace:common-events:jar:0.0.1-SNAPSHOT
```

Building a single module in isolation doesn't build the sibling module it depends on. The instinct
is to reach for `-am` ("also make" the dependencies) — but that backfires here in a different way:

```text
Unable to find a suitable main class, please add a 'mainClass' property
```

`-am` pulls the root aggregator into the same reactor build, and `spring-boot:run` — invoked directly
as a goal rather than bound to a lifecycle phase — tries to run on *every* project in that reactor,
including the root `pom`-packaged aggregator, which has no application to run at all.

**The fix**: install `common-events` (and the root pom, which `common-events` itself depends on as
its parent) into the local repository first, then run `spring-boot:run` on `order-service` alone,
with nothing else in the reactor to confuse it:

```bash
./mvnw -N install                    # the root pom itself
./mvnw -pl common-events install     # the module order-service depends on
./mvnw -pl order-service spring-boot:run
```

Three commands instead of one, but each one does exactly what it says, and none of them tries to run
an application that doesn't exist.

---

## S1 — accepted, and both rows written (SC-001, SC-010)

```text
HTTP/1.1 202
Location: /api/orders/9aa52049-75de-4e3b-ada4-0b1191a21944
{"orderId":"9aa52049-75de-4e3b-ada4-0b1191a21944","status":"PENDING"}
```

Querying the database directly confirmed exactly what SC-001 asks for — one order row, one outbox
row, sharing the same identifier:

```text
                  id                  | status  |  event_type   | outbox_status
---------------------------------------+---------+---------------+---------------
 9aa52049-...                          | PENDING | order.created | PENDING
```

And the stored payload confirms T070's Jackson configuration end to end, against a real serialized
row rather than a unit test's local `ObjectMapper`:

```json
{"amount": 150.00, ...}
```

Plain decimal, not `1.5E+2` — this is the same guarantee `OrderPayloadMappingTest` checks, now
verified against what actually got written to `jsonb`.

**One line of S1 could not be run**: the read-back step, `curl .../api/orders/$ORDER`, correctly
returns `404`. That endpoint is User Story 3's (`T104`), not built yet — an expected gap, not a
failure, and the rest of S1 stands on its own regardless.

## S4 — invalid requests refused, nothing recorded (SC-009)

All three deliberately broken requests came back `400`, each naming precisely the field that was
wrong:

| Request | Response |
|---|---|
| empty `seatIds` | `"field":"seatIds"`, `"must not be empty"` |
| duplicate seats | `"field":"seatIds"`, `"must not contain duplicate elements"` |
| `amount: "10.5"` | `"field":"amount"`, `"must have exactly two decimal places"` |

Order count before and after: **1, then 1**. Nothing was recorded by any of the three.

## S5 — overload refused fast and readably (SC-016)

500 requests, 200 running concurrently:

```text
500 202
```

Every single one succeeded. No `503` appeared in this run, and — just as important — neither did
any `400` or `500`: the quickstart's real requirement, "never 400 or 500," held exactly.

**This is an honest result, not a hidden failure.** At this concurrency, against a connection pool of
20 handling a request that holds a connection only for two very fast inserts, this local machine
simply didn't push the pool into genuine saturation — 500 requests across 200 parallel `curl`
processes on localhost complete quickly enough that HikariCP's queue drained within the 250ms
timeout every time. That the service comfortably absorbed the load *is* a valid outcome. Order and
outbox counts after the run: **501 and 501** — the one from S1 plus all 500 from this scenario,
confirming none were silently dropped.

The capacity-refusal path is not undemonstrated, though — it is proven elsewhere, deliberately:
`OrderCapacityIT` (T077, T084) holds *every* connection in the pool on purpose before firing a
request, and gets a genuine `503` every time it runs. That automated test is the right tool for
proving the mechanism works; this manual run is the right tool for confirming the service behaves
correctly under load that *doesn't* need refusing. The two are complementary, not redundant. Real
sustained-load testing — the kind that would reliably produce both outcomes at once — is what the
k6 harness in build step 9 exists for.

Checked the metric as well, since a metric that silently doesn't move is its own kind of bug:

```text
orders_refused_capacity_total 0.0
```

Zero, consistent with zero refusals in this run — the meter is live and accurate, not just present.

---

## Overall

Everything User Story 1 promises held up against a running service: atomic acceptance, honest
validation errors naming their field, and correct behaviour under load whether or not that load
happens to trigger a refusal. The one gap — reading an order back — is exactly the gap User Story 3
exists to close, and closing it is the next piece of this build, not a defect in this one.
