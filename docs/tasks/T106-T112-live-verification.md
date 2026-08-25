# T106, T108, T109, T112: proving Phase 6's remaining claims against a running system

The rest of Phase 6 was code and docs. These four tasks are different: each one is a question that
can only be answered by actually running the service and watching what happens, not by reading the
source. All four were run in one continuous session, on this machine, against real Docker containers.

## T106 — the five R12 meters, and proof the gauges are live (FR-024, FR-031)

```
orders_refused_capacity_total 0.0
outbox_oldest_pending_age_seconds 0.0
outbox_records_parked 3.0
outbox_records_published_total 0.0
outbox_send_failures_total 0.0
```

All five present — Micrometer's Prometheus naming convention appends `_total` to the three counters,
which is expected, not a naming mismatch. To prove the parked-count gauge reads the database live
rather than remembering a number: inserted a fresh poisoned row, waited for it to exhaust its five
attempts, and re-scraped. The gauge moved from `3.0` to `4.0`, matching a direct `SELECT count(*)`
against the table exactly. **Confirmed.**

(One retry along the way, worth recording: the first attempt reused an aggregate id that already had
an earlier row permanently `PARKED` from a previous test run. The new row correctly stayed `PENDING`
forever and the gauge never moved — not a bug, but FR-030 doing exactly its job: a later row for an
order with an already-parked earlier row must never be sent out of turn. Redone with a fresh id.)

## T108 — one connected trace (FR-026, SC-012)

Switched to `COMPOSE_PROFILES=core,obs`, restarted, submitted one booking, then queried Zipkin's API
directly rather than eyeballing the UI:

```
trace 6a8db361f4e3af3ce24e9802b58d6f77
  http post /api/orders   id=e24e9802b58d6f77  parent=None
  outbox.publish          id=8b504f8182bc3d15  parent=e24e9802b58d6f77
```

One trace, two spans, `outbox.publish` correctly parented under the accepting request — not two
unrelated traces that merely happen to concern the same order. **Confirmed.**

## T109 — throughput, latency, backlog drain (FR-032, FR-033, SC-003/014/015 — interim check)

`ab` wasn't available and installing it needed a `sudo` password this session doesn't have — rather
than work around that, a small Python script (`ThreadPoolExecutor` + `requests`) fired the same shape
of load: 5,000 requests, 200 concurrent.

```
Sustained rate: 1088.6 req/sec   (target: >200/sec)
Status codes: {202: 5000}         (zero failures)
Latency p50: 51.0ms  p95: 155.8ms  p99: 230.4ms   (target: p95 <300ms)
```

Backlog after the run: `2162 → 1362 → 462 → 1` across four 5-second checks — drained in well under 30
seconds. The one row that never reached zero was the same permanently-parked-predecessor artifact
from T106's aggregate id, not part of this burst. **Confirmed. This is the interim measurement —
step 9's k6 script is the durable, repeatable version of this check.**

## T112 — the whole quickstart, on a clean environment (every SC in spec.md)

`make down && make up` (fresh volumes, `core,obs` so S6 could run too), `order-service` restarted —
Flyway replayed both migrations from empty, confirming SC-011 directly in the startup log.

| Scenario | Result |
|---|---|
| **S1** — both rows written, read back unchanged | One order row, one `order.created` outbox row, GET echoed every submitted field. **Confirmed** (SC-001, SC-010). |
| **S2** — message keyed correctly, plain decimal | Already verified in an earlier session — see `docs/tasks/T101-quickstart-scenarios.md`. Not re-run here. |
| **S3** — atomicity survives a crash | Already verified — see T101. Not re-run here. |
| **S4** — invalid requests refused, nothing recorded | `400 400 400`, each naming its field, `orders` count unchanged (1 before, 1 after). **Confirmed** (SC-009). |
| **S5** — overload refused fast and readably | 500/500 concurrent requests returned `202` — no refusal was actually triggered at this load, so no `500`s and no `400`s misreporting overload either. The 503 path itself is separately proven by the dedicated `OrderCapacityIT` test, which forces pool exhaustion directly. **No defect found** (SC-016). |
| **S6** — one connected trace | Re-run fresh on this reset environment: 501 multi-span traces, each `http post /api/orders` → `outbox.publish` correctly parented. **Confirmed** (SC-012). |
| **S7** — a parked row stalls only its own order | Re-run fresh: poisoned row reached `PARKED`/`attempts=5`/`last_error` populated; a concurrently-submitted healthy order reached `PUBLISHED` promptly, unaffected. **Confirmed** (SC-013). |
| **S8** — throughput, latency, backlog drain | Re-run fresh: 1110.0 req/sec, p95 144.1ms, backlog `2400 → 1600 → 700 → 0` — reached zero cleanly this time, no stale blocked row on the fresh environment. **Confirmed** (SC-003, SC-014, SC-015). |

Every scenario is either confirmed directly above, confirmed in the earlier T101 session (S2, S3),
or — for S5 specifically — produced no defect while not exercising the refusal branch, which the
project's own automated test suite already covers independently. Nothing in spec.md's success
criteria for this build step is left unaccounted for.

`infra/.env` was returned to its committed `COMPOSE_PROFILES=core` default afterward; the temporary
`obs` switch was only needed for the tracing checks above.
