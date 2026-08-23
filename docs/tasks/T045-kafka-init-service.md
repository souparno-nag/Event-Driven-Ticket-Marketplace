# T045 — Wiring the provisioner in, and the flag that made it awkward

**What this task did:** added the `kafka-init` service to `docker-compose.yml` and made `make up`
run it, so the fourteen channels exist automatically once the environment is up.

It also deviates from the plan in one specific way, for a reason that was measured rather than
assumed. That deviation is most of what this document is about.

---

## A job is not a component

Every service written so far is a **long-running component**: it starts, it stays up, it serves
requests, and "healthy" is a meaningful thing to ask about it.

`kafka-init` is a **job**. It connects to the broker, creates fourteen channels, prints what it did,
and exits. Success for a job is *terminating with exit code 0* — the opposite of what success means
for a component. This pattern is common enough to have a name in Kubernetes (an **init container**),
and the shape is always the same: do one thing that must happen before the real work, then get out
of the way.

Most of the difficulty below comes from tooling that assumes everything is a component.

---

## The trap, found in T039

`make up` passes `--wait`, which blocks until every started container reports healthy. That flag is
what makes `make up` mean "ready" rather than "started".

But a container that has exited is neither running nor healthy, and **Compose cannot tell "finished
its job" from "died"**. Measured against Compose v2.39.1 with a deliberately trivial job:

```
container probe-init-1 exited (0)
EXIT=1
```

Exit code **0**. The job succeeded. `--wait` failed the entire command anyway.

Had this been discovered by shipping it, the symptom would have been thoroughly confusing: every
`make up` reporting failure *after a completely successful startup*, with all six components healthy
and one line about a container that exited correctly. Scenario 4's ten-cycle loop would have broken
on cycle one.

---

## Three ways out, and why the third won

**1. Put the job in `core`/`full` as planned, and accept the failure.** Not an option — it makes the
project's primary command lie.

**2. Have another service declare that the exit is expected.** Compose does understand a completed
job, if something depends on it that way:

```yaml
depends_on:
  kafka-init:
    condition: service_completed_successfully
```

This was verified to work — the same probe returns exit 0 with that edge added. But no component
here consumes channels, so satisfying it would mean **inventing a service purely to make a flag
happy**. A dependency that exists to satisfy tooling rather than to describe reality is exactly the
kind of thing T038 refused to add.

**3. Keep the job out of the `up --wait` set entirely, and invoke it explicitly.** This is what
was done. `kafka-init` is tagged `profiles: ["init"]`, a profile nothing normally activates, so
`up --wait` never sees it. `make up` then runs it as a second step:

```makefile
up:
	$(COMPOSE) up -d --wait --wait-timeout 300
	@if $(COMPOSE) config --services | grep -qx kafka; then \
	  $(COMPOSE) run --rm kafka-init; \
	fi
```

### Why `run` rather than `up` for the second step

`docker compose run` is the right primitive for a job: it runs the container in the foreground,
waits for it to finish, and **propagates its exit code**. Verified explicitly, because the whole
value of the approach rests on it:

| Job exits | `compose run` exits |
|---|---|
| 0 | 0 |
| 3 | **3** |

So failed provisioning still fails `make up` — the loud failure is preserved, just moved from the
wrong mechanism to the right one. Using `up -d kafka-init` instead would have started the job and
returned immediately, reporting success whether or not any channel was created.

`compose run` also enables the target service's profile automatically, so `init` never needs to be
switched on by hand.

### The guard

The `obs` profile runs Zipkin and Prometheus with no broker at all, so provisioning must be skipped
there. The check asks `config --services` whether Kafka is active — the same profile-derived list
`make health` reads (T040), rather than a second assumption about which profiles contain a broker.
Verified: `core` yes, `full` yes, `obs` no.

### The bonus: `make health` stays honest

Because `init` is not among the active profiles, `config --services` never offers `kafka-init` to
the health target. That is correct rather than convenient — **a job that has finished is not a
component whose health can be reported.** Had it been tagged `core`/`full`, `make health` would have
had to carry a special case for it forever.

---

## What the service declares

```yaml
kafka-init:
  image: confluentinc/cp-kafka:7.7.1     # the broker's own image, reused
  profiles: ["init"]
  depends_on:
    kafka:
      condition: service_healthy
  volumes:
    - ./kafka-init:/opt/kafka-init:ro
  environment:
    BOOTSTRAP_SERVER: kafka:29092
  command: ["bash", "/opt/kafka-init/create-topics.sh"]
  restart: "no"
```

**The image is reused, not added.** Confluent's Kafka image already contains the `kafka-topics` CLI
and is guaranteed version-matched to the broker. It is cached locally by the time this runs, so
reuse costs nothing while a second image would cost a download and a version to keep aligned.

**`depends_on: kafka: service_healthy` is the environment's only real ordering edge.** T038 examined
all six components and found none — this is the one the analysis predicted would arrive here.
Channels cannot be created before the broker serves metadata, and `service_healthy` reads the
broker's own readiness probe rather than merely waiting for a container to exist.

It also satisfies the profile rule T038 uncovered — a dependency's profiles must be a superset of
its dependent's — from the other direction: Kafka is in `core, full` and the job is in `init`, so
the edge is only ever evaluated when `compose run` activates `init`, at which point Kafka is
already up.

**`restart: "no"` is explicit** because the default restarts on failure, which for a job means
retrying forever instead of reporting that provisioning failed.

---

## The `NewTopic` tradeoff, recorded

R4 originally specified Spring `NewTopic` beans: declare the channels in a configuration class and
let `KafkaAdmin` create them at application startup, with idempotency handled for free.

It is a genuinely nicer design, and it cannot be used yet. **FR-020 requires the fourteen channels
to exist once the environment is running, and SC-009 checks them on a freshly started environment —
but at build step 1 no Spring application exists.** None will until step 2. Provisioning through a
bean would make starting the environment depend on a jar having been built, which is precisely the
chicken-and-egg the infrastructure layer exists to avoid.

The cost, named in the compose file rather than left implicit: channel names now live in two places
with nothing enforcing agreement. **T046** closes it from the Java side. Once the services exist,
`NewTopic` beans can be added alongside this script — both are idempotent, so they would not
conflict.

---

## In one line

The provisioner runs as a job rather than a component, kept out of `--wait` because Compose cannot
distinguish a job that finished from one that died, and invoked through `compose run` because that
is the one mechanism that both waits for it and reports its failure.
