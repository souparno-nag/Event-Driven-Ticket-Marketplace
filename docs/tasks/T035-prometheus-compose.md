# T035 — Prometheus, and the one line that is Linux-specific

**What this task did:** added the `prometheus` service. It contains the only setting in this file
that exists because of a difference between Docker Desktop and the native Linux engine.

---

## How Prometheus differs from everything else here

Every other component in this file **waits to be called**. Prometheus does the opposite: it
**reaches out** on a timer and pulls metrics from the things it monitors.

```
Prometheus ──every 15s──► order-service:8081/actuator/prometheus
           ──every 15s──► inventory-service:8082/actuator/prometheus
```

That is the **pull model**, and it is worth understanding as a design choice rather than an
implementation detail:

| | Push (service sends metrics) | Pull (Prometheus fetches) |
|---|---|---|
| Service knows about | the metrics backend | nothing |
| A dead service looks like | silence — same as a quiet one | a failed scrape — unambiguous |
| Adding a monitor | reconfigure every service | one line in one file |

The second row is the strongest argument. Under push, a service that has crashed and a service with
nothing to report both send nothing. Under pull, a crashed service is a *failed scrape*, which is
itself a signal — Prometheus knows it *should* have got an answer.

The direction of that connection is exactly what makes the next section necessary.

---

## `host.docker.internal`, and why it is declared explicitly

```yaml
extra_hosts:
  - "host.docker.internal:host-gateway"
```

In build steps 2–7 you will run the Spring Boot services **from your IDE**, on your machine, not in
containers. So Prometheus, inside a container, needs to reach a process on the host.

The obvious attempt fails:

```yaml
targets: ["localhost:8081"]     # ← inside a container, localhost is the container itself
```

`localhost` always means "this container". Prometheus would be scraping itself on port 8081 and
finding nothing.

`host.docker.internal` is the conventional name for "the machine running Docker". The catch:

> **Docker Desktop provides it automatically. The native Linux engine does not.**

And this project runs on the native engine — decision R9 switched to it deliberately, since Docker
Desktop's Linux VM costs 1–2 GiB of overhead and imposes a fixed memory ceiling.

So the name has to be created by hand. `host-gateway` is a magic value Docker resolves to the host's
address on the container network, and `extra_hosts` writes that into the container's `/etc/hosts`.

Without the line, scrapes fail with `no such host` — on a machine where the identical configuration
works fine for a colleague on Docker Desktop. **A difference in the container runtime, wearing the
costume of a configuration bug.** Those are miserable to debug precisely because the config is
correct, so R9 flagged it in advance and this line is the answer.

Note it appears on Prometheus alone. Nothing else here initiates connections outward.

---

## The image tag says `-busybox`, on purpose

```yaml
image: prom/prometheus:v3.13.2-busybox
```

Prometheus publishes several variants of each release, and the interesting pair is:

- **`-busybox`** — includes a minimal shell and utilities, including `wget`
- **`-distroless`** — the Prometheus binary and nothing else. No shell, no `wget`, no `sh`

Distroless images are excellent: smaller, and dramatically less attack surface, since an attacker
who gets code execution finds no tools to work with.

They also break this:

```yaml
test: ["CMD", "wget", "--quiet", "--tries=1", "--spider", "http://localhost:9090/-/healthy"]
```

No `wget` in the image means the probe cannot run, and the container sits permanently unhealthy for
a reason that has nothing to do with Prometheus. Same lesson as Zipkin's probe in T034, but sharper
— here the *same software* ships in two forms and only one can run the check.

Choosing the variant explicitly is better than relying on what the plain tag happens to point at
today. If this were production, the right answer would be distroless plus a probe that needs no
shell.

---

## `--storage.tsdb.retention.time=6h`

Prometheus defaults to keeping **fifteen days** of metrics on disk. Nobody is investigating last
Tuesday on a local demo, and this is a machine where memory and disk are both being budgeted
carefully. Six hours comfortably covers a working session, including a load-test run.

---

## The config file (T036) is deliberately almost empty

`infra/prometheus/prometheus.yml` sets scrape intervals and exactly one job: **Prometheus scraping
itself.**

There are no service targets, and their absence is a decision rather than an omission. Adding
`order-service:8081` now — before order-service exists — would leave a target permanently DOWN. And
a dashboard with a permanent red light is worse than no dashboard, because it teaches you to ignore
red. Monitoring that is normally broken is monitoring nobody reads.

The self-scrape job earns its place: it is the simplest proof the scrape loop works end to end. If
that target is UP, the pipeline is sound and any other failing target is that target's problem.

The file also carries the future job as a comment, with the trap called out:

```yaml
#   - job_name: order-service
#     metrics_path: /actuator/prometheus
#     static_configs:
#       - targets: ["host.docker.internal:8081"]   # NOT localhost
```

Note `metrics_path`. Prometheus defaults to `/metrics`; Spring Boot Actuator exposes
`/actuator/prometheus`. Forgetting that is a common first-time failure, and the comment saves the
discovery.

---

## Try it yourself

```bash
docker compose -f infra/docker-compose.yml --profile obs up -d prometheus
```

Open <http://localhost:9090> and go to **Status → Targets**.

**Expect**: exactly one target, `prometheus (1/1 up)`. That single green row is the scrape loop
proving itself.

Now demonstrate the `host.docker.internal` mechanism, which is easier to believe once seen:

```bash
docker exec prometheus cat /etc/hosts | grep host.docker.internal
```

**Expect**: a line mapping it to a gateway address such as `172.17.0.1`. That entry is what
`extra_hosts` wrote, and it exists in no other container here:

```bash
docker exec redis cat /etc/hosts | grep host.docker.internal || echo "absent — as expected"
```

And confirm the retention setting took:

```bash
curl -s localhost:9090/api/v1/status/flags | python3 -m json.tool | grep retention
```

**Expect**: `"storage.tsdb.retention.time": "6h"`.

Finally, query something. In the Prometheus UI's expression box:

```
prometheus_http_requests_total
```

**Expect**: rows of its own HTTP metrics. Those are metrics Prometheus collected *from itself* — the
same shape of data Spring Boot's `/actuator/prometheus` will produce in step 8.

---

## What comes next

**T033** — Eureka is still outstanding. It is the one component with no official image, and the
options need a decision rather than a guess.

After that: **T037** (`infra/.env`), **T038** (`depends_on` health conditions), and **T039–T040** (the
Makefile), which is where `make up` finally exists.
