# T036 — The Prometheus scrape configuration

**What this task did:** created `infra/prometheus/prometheus.yml` — the file T035's container mounts
read-only. It configures scrape timing and exactly one job.

---

## Why it is nearly empty

The obvious thing to write is the list of services to monitor. That list is deliberately absent, and
the absence is the point.

order-service does not exist yet. Neither do the other five. Adding this now —

```yaml
- job_name: order-service
  static_configs:
    - targets: ["host.docker.internal:8081"]
```

— would give Prometheus a target it can never reach, so **Status → Targets** would show a permanent
red row from day one.

That is worse than having no monitoring, for a reason worth internalising:

> A dashboard that is always partly red teaches you to ignore red.

Once "some targets are always down" is normal, the day a *real* target goes down nobody notices. The
signal has been spent. Monitoring is only useful while its alerts still mean something, and the
fastest way to destroy that is to ship known-failing checks.

So the targets arrive in build step 8, when there is something to point at. The file carries the
future job as a **comment** instead, which documents the intent without emitting a false alarm.

---

## The one job that is here

```yaml
- job_name: prometheus
  static_configs:
    - targets: ["localhost:9090"]
```

Prometheus scraping itself. Not filler — it is the simplest possible end-to-end proof that the
scrape loop works:

- If this target is **UP**, the pipeline is sound: Prometheus is running, its config parsed, it can
  perform an HTTP scrape and store the result. Any *other* target failing is then that target's
  problem.
- If this target is **DOWN**, the problem is Prometheus itself and no other result can be trusted.

Note `localhost` is correct *here* and wrong for every future job. Inside the container, `localhost`
is Prometheus — which is exactly what this job wants and exactly what a job targeting your host-run
services must avoid. Hence T035's `host.docker.internal`, and the reminder in the commented example.

---

## The timing settings

```yaml
scrape_interval: 15s
scrape_timeout: 10s
evaluation_interval: 15s
```

**`scrape_interval`** is how often metrics are collected, and it sets the resolution of everything
you can see. A spike shorter than the interval is invisible — Prometheus samples, it does not
observe continuously. 15s is fine for watching the step-9 load test, where the interesting behaviour
lasts minutes.

Shorter is not free: every scrape is an HTTP request to each service and a write to the time-series
database. Monitoring that measurably loads the system it monitors is its own problem.

**`scrape_timeout` must stay below `scrape_interval`.** If a scrape could outlive its interval, the
next one would start while the previous is still running, and they would pile up. Prometheus rejects
a config where timeout exceeds interval — a small, welcome example of a tool refusing to let you
build something that cannot work.

**`evaluation_interval`** governs how often alerting and recording rules are evaluated. There are no
rules yet; it is stated so the file has an obvious place to grow.

---

## A note on how the file is mounted

From T035:

```yaml
volumes:
  - ./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
```

This is a **bind mount** — a host file mapped into the container — unlike the *named volumes* used
for Kafka, PostgreSQL, and Elasticsearch data. The distinction:

| | Bind mount | Named volume |
|---|---|---|
| Lives at | a path you choose on the host | Docker's own storage |
| For | config you edit and commit | data the container generates |
| Visible in git | yes | no |

Configuration belongs in version control; a database's internal files do not.

`:ro` makes it read-only. Prometheus never needs to write its own config, and read-only means no bug
or bad reload can modify a file on your machine. Cheap, and worth doing by default for any mounted
config.

---

## Try it yourself

The file parses as valid YAML with the expected shape:

```bash
python3 -c "import yaml; d=yaml.safe_load(open('infra/prometheus/prometheus.yml')); print(d['global'], [j['job_name'] for j in d['scrape_configs']])"
```

**Expect**: the three global settings, and `['prometheus']`.

Prometheus can also check the file properly, using its own validator inside the container:

```bash
docker run --rm -v "$PWD/infra/prometheus/prometheus.yml:/tmp/p.yml:ro" \
  --entrypoint promtool prom/prometheus:v3.13.2-busybox check config /tmp/p.yml
```

**Expect**: `SUCCESS: /tmp/p.yml is a valid prometheus config file`. Better than a YAML parse —
`promtool` validates the *semantics*, so it catches a misspelled key that YAML would happily accept.

Worth seeing the timeout rule enforced, since it is a nice example of a tool refusing an impossible
config. Temporarily set `scrape_timeout: 30s` (above the 15s interval) and re-run `promtool`.

**Expect**: an error stating the scrape timeout is greater than the scrape interval. Revert it.

Then, with the container running:

```bash
docker compose -f infra/docker-compose.yml --profile obs up -d prometheus
curl -s localhost:9090/api/v1/targets | python3 -m json.tool | grep -E '"health"|"scrapeUrl"'
```

**Expect**: one target, `"health": "up"`, scraping `http://localhost:9090/metrics`.

---

## What comes next

**T033** — Eureka, the outstanding one. Then **T037** (`infra/.env`), **T038** (`depends_on` health
conditions), and **T039–T040**, which is where `make up` starts existing.
