# T040 — `make health`, and the list that must not be hardcoded

**What this task did:** added a `health` target to the `Makefile` that prints one line per
component, **with the list of components derived from the active profile** rather than written out
in advance.

```
  ok    kafka            healthy
  ok    postgres         healthy
  FAIL  redis            unhealthy
  -> not ready. 'make logs SERVICE=<name>' shows why; 'make up' starts what is missing.
```

---

## Why a separate target when `make up` already fails

`make up` passes `--wait` (T039), so it already exits non-zero on an unhealthy environment. But it
fails as a **single verdict**: something did not come up. FR-016 asks for something different —
that a developer can identify *which* component is unhealthy "without reading raw logs".

That distinction is the whole point. Given `make up` failed, the alternative is
`docker compose logs`, which is thousands of lines from six components interleaved, and the reader
has to already suspect which one to blame. One line per component answers "where do I look?" in
about a second, and the summary line then names the exact command that shows why.

So `up` is the gate and `health` is the diagnosis. They answer different questions.

---

## The mistake this target exists to avoid

The obvious implementation is a list:

```makefile
health:
	@check kafka; check postgres; check redis; check elasticsearch; \
	 check eureka; check zipkin; check prometheus
```

This is wrong, and it is wrong in a way that gets worse over time.

The project runs profiles (T037). Under `core` — the default, and what build steps 1–5 use — only
**three** of the six components are supposed to be running. Elasticsearch, Zipkin, and Prometheus
are absent **on purpose**. A hardcoded list reports them as failures every single time you run it:

```
  FAIL  elasticsearch    not created     <- correct behaviour, reported as a fault
  FAIL  zipkin           not created
  FAIL  prometheus       not created
```

A health report that cries wolf on a perfectly healthy environment is worse than no health report,
because it teaches the reader to skim past red output. Once that habit forms, the one line that
*is* a real failure gets skimmed past too. **A check that is wrong by design trains people to
ignore checks.**

The list would also carry the seventh error the spec's own wording contains: the requirement talks
about seven components, but Eureka was deferred to build step 7 (T033), so a list transcribed from
the spec reports a permanent failure for a component that does not exist yet.

---

## Deriving the list instead

```makefile
services="$(docker compose -f infra/docker-compose.yml config --services | sort)"
```

`config --services` returns the services the **active profile** enables, computed by Compose from
the same `COMPOSE_PROFILES` that `make up` obeyed. That is what makes it correct: it is not a
second list that has to be kept in sync with the first — there is only one list, and both targets
read it. Add a service to `docker-compose.yml` and `make health` covers it with no edit here.

Verified against all three profiles with nothing running:

| `COMPOSE_PROFILES` | Components reported |
|---|---|
| `core` | 3 — kafka, postgres, redis |
| `obs` | 2 — prometheus, zipkin |
| `full` | 6 — the above plus elasticsearch |

The counts track the profiles exactly, and Eureka never appears, because Compose can only report
services that exist.

---

## Reading each component's state

```makefile
docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}:{{.State.ExitCode}}{{end}}' $cid
```

**Why `docker inspect` rather than parsing `docker compose ps`.** `ps` renders a status for humans:
`Up 4 minutes (healthy)`. Extracting the health from that means matching on the shape of a string
that exists to be read, not parsed — and that string is free to change wording between Docker
versions. `inspect` with a template returns the state field itself.

The template asks two questions in order:

1. **Does this container declare a health check?** If yes, report its result — `healthy`,
   `unhealthy`, or `starting`. This is the readiness answer FR-012 cares about.
2. **If not**, fall back to the raw container state plus its exit code.

That fallback distinguishes "running but nobody defined what ready means" from "actually ready",
and it is what makes the one-shot case below work.

### The states, and why each is judged the way it is

| Reported | Mark | Reasoning |
|---|---|---|
| `healthy` | `ok` | The component's own readiness probe passes. |
| `starting` | `WAIT` | Inside its `start_period`. Not a failure yet, but not ready — so not `ok`. |
| `unhealthy` | `FAIL` | The probe ran and did not pass. |
| `running`, no probe | `ok` | Running is all that can be asserted; the line says so explicitly. |
| `exited:0` | `ok` (`completed`) | See below. |
| `not created` | `FAIL` | The container does not exist. Usually "you have not run `make up`". |

**The `exited:0` case is there for T045.** The topic provisioner arriving in that task creates
fourteen channels and exits — that is the entire design of an init container. Judged naively it
looks identical to a crash: not running, not healthy. Reporting a successful provisioner as a
failed component would make a completely correct environment show a permanent red line, which is
the same cry-wolf problem in a different costume. Exit code 0 means it did its job, so it reports
`completed`.

---

## Exit code

`make health` exits non-zero if any component is not ready, so it works in a script and not just on
a screen. A health check that always succeeds is not a health check.

The visible cost is that `make` prints its own `*** [health] Error 1` line underneath. That was
accepted rather than worked around: suppressing it would mean the target reporting success while
displaying failures, and a command that lies about its exit status is a worse problem than an ugly
line of output.

---

## How it was verified

Each state was produced deliberately using throwaway busybox containers, with the real recipe run
against them by overriding one variable on the command line — so what was tested is the Makefile
target itself, not a copy of it:

```bash
make health COMPOSE="docker compose -f /tmp/.../health.yml"
```

The probe defined four services: one whose check passes, one whose check is `false` and therefore
always fails, one that echoes and exits, and one with no health check at all.

```
  FAIL  bad              unhealthy
  ok    good             healthy
  ok    nocheck          running (no health check declared)
  ok    oneshot          completed
```

A second probe covered the remaining two paths — `starting`, caught by checking during a 30-second
`start_period`, and the all-green case:

```
  ok    good             healthy
  ok    oneshot          completed
  ok    slow             healthy
EXIT=0
```

Exit 0, no summary line, no `make` error. Every branch of the `case` statement has now been
observed against a real container rather than reasoned about.

The real environment was not started for this — that is Scenario 3's job in T042, and it needs
image pulls. Using disposable containers tested the logic in seconds instead of minutes, and
tested failure states that are hard to produce on purpose with real components.

---

## In one line

`make health` answers "which component is broken?", and it stays trustworthy because it asks
Compose which components are supposed to be running instead of assuming it already knows.
