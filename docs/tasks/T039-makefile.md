# T039 — The Makefile: four commands, and the flags that matter

**What this task did:** created a root `Makefile` with four targets — `up`, `down`, `build`,
`logs` — so the environment and the build are driven by short commands instead of remembered
invocations.

```bash
make up      # start the active profile's components, wait until they are healthy
make down    # stop everything and delete its data — the clean reset
make build   # ./mvnw clean verify at the root
make logs    # follow logs (optionally: make logs SERVICE=kafka)
```

---

## Why a Makefile at all

Every command here could be typed by hand. `make up` is really:

```bash
docker compose -f infra/docker-compose.yml up -d --wait --wait-timeout 300
```

Nobody types that twice, and nobody types it identically twice. The problem is not length — it is
that **the flags are load-bearing**. Drop `--wait` and `make up` still appears to work while
returning before anything is ready. A file that pins the exact invocation is the only way those
details survive.

A Makefile beats a folder of shell scripts for three practical reasons: one file lists everything a
developer can run here, there is no executable bit or shebang to get wrong, and `make` plus
tab-completion answers "what can I do in this repo?" without opening a README.

`make` is a build tool from 1976 that has outlived most of what it was built for, and the reason is
this: a target name mapped to a command, with the command written down where it can be reviewed.

---

## The two lines of Make syntax worth understanding

Almost everything in this file is a shell command. Two lines are not, and both prevent a real bug.

### `.PHONY: up down build logs`

Make's original job is producing **files**. When you write a target named `build`, Make assumes you
are describing how to produce a file called `build` — and it skips the work if that file already
exists and looks newer than its inputs.

That is a live hazard here, because `build` is exactly the name a directory tends to take. The
moment a `build/` directory appears in the repo root, `make build` would print

```
make: 'build' is up to date.
```

and compile nothing. No error, no warning — the build simply stops happening, and you find out
later from a stale jar.

`.PHONY` says these targets are commands, not files, so they always run. It was verified rather than
assumed: creating a `build/` directory and running `make -n build` still shows `./mvnw clean verify`.

### `.DEFAULT_GOAL := up`

Running bare `make` runs the **first** target in the file. That means the behaviour of `make`
depends on the order the targets happen to be written in, and reordering the file for readability
would silently change what the default command does. Naming the default explicitly makes it a
decision rather than a side effect of formatting.

---

## `-f` rather than `cd infra`

```makefile
COMPOSE := docker compose -f infra/docker-compose.yml
```

The obvious alternative is `cd infra && docker compose ...`. It is rejected because a recipe that
changes directory makes *every* other path in the file relative to somewhere else — and `./mvnw`
lives at the root, so `build` and `up` would need different working directories.

The reason `-f` is safe is worth knowing: Compose derives its **project directory** from the
compose file's own location, not from where you happen to be standing. Both of the things that
depend on that were checked from the repo root:

- `infra/.env` is still read — `docker compose -f infra/docker-compose.yml config --services`
  returns the three `core` services, which only happens if `COMPOSE_PROFILES=core` was picked up.
- The Prometheus bind mount `./prometheus/prometheus.yml` still resolves, to
  `…/infra/prometheus/prometheus.yml` and not to a non-existent path at the repo root.

---

## `up`: why `--wait` is not optional

This is the most important flag in the file.

`docker compose up -d` returns as soon as containers are **created**. Created is a long way from
useful: PostgreSQL's container exists for seconds before it accepts connections, and Elasticsearch
can take a minute. So a plain `up -d` hands you a shell prompt while nothing works yet.

Normally some of that gap is covered by `depends_on`, but T038 established that **none of the six
components has an ordering dependency**, so there are no edges and nothing waits for anything.
Without `--wait`, nothing in the entire environment gates on readiness at all — and
`make up && make health` (Scenario 3) would report failures that were only ever components still
booting.

`--wait` blocks until every started container passes the health check written for it in T029–T035,
and **exits non-zero if any never does**. That is what turns `make up` into a truthful signal, and
it is what lets Scenario 4 chain ten teardown-and-restart cycles with `||` and trust the outcome:

```bash
make up || { echo "FAILED on cycle $i"; break; }
```

That line is worthless if `make up` returns 0 for a broken environment.

### `--wait-timeout 300`

SC-002 requires every component healthy within five minutes. Without a timeout, a component that
never becomes healthy hangs the terminal indefinitely — you eventually press Ctrl-C and guess.
Setting 300 puts the spec's number into the command that enforces it: five minutes, then a clear
failure and your prompt back.

---

## `down -v`, and the tradeoff behind it

`-v` deletes the named volumes, so `make down` is a **reset**, not a stop. Kafka's log directory,
PostgreSQL's data, and the Elasticsearch index are all destroyed.

The alternative — a gentle `down` that keeps data, with a separate `reset` for the destructive
version — was considered and rejected, and the file records why:

- The failure it prevents is likely and badly disguised. Kafka stamps its cluster id into its data
  directory on first start and refuses to boot against a directory holding a different one. A
  surviving volume turns the next `make up` into a cryptic metadata mismatch that looks nothing
  like "you have stale data".
- **FR-015 and SC-005 are literally a measurement of teardown-and-restart.** A reset that is only
  clean when you remember an extra flag is not a reset.
- Nothing here holds data worth keeping. It is generated demo state, so the usual reason to
  preserve volumes does not apply.

A real deployment would invert this default without hesitation. Local demo environments and
production have genuinely opposite defaults here, and that is fine as long as it is deliberate.

---

## `build`: `verify`, not `install`

```makefile
build:
	./mvnw clean verify
```

Maven runs through a fixed sequence of phases, and naming one runs everything up to it. `verify`
compiles, packages, and runs the tests. The next phase, `install`, additionally copies the built
jars into `~/.m2` — a directory shared by every Maven project on the machine.

`verify` is the right stopping point because checking that the code works should not modify
machine-global state as a side effect. `install` becomes necessary only when a *separate* project
needs to consume these jars; inside one reactor build, modules already resolve each other directly.

Verified: `make build` runs 31 tests across `common-events` and reports BUILD SUCCESS.

---

## `logs`, and the optional argument

```makefile
logs:
	$(COMPOSE) logs -f --tail=100 $(SERVICE)
```

`-f` follows, because the reason to open logs is usually to watch something happen. `--tail=100`
gives enough history to see why a container is unhealthy without replaying its whole startup.

`$(SERVICE)` is unset by default and expands to nothing, which Compose reads as "all services". So
one target covers both uses:

```bash
make logs                 # everything in the active profile
make logs SERVICE=kafka   # just the broker
```

Passing `NAME=value` on the command line is standard Make, and it beats writing a `logs-kafka`,
`logs-postgres`, `logs-redis`… target per component.

---

## A finding that lands on T045

While confirming what `--wait` does, a trap turned up that affects the topic provisioner coming in
T045. A **one-shot** container — one that does its job and exits, which is exactly what a topic
creator is — makes `--wait` fail:

```
container probe-init-1 exited (0)
EXIT=1
```

Exit code **0**. The job succeeded. `--wait` still failed the whole command, because it sees a
container that is neither running nor healthy and cannot tell "finished" from "died".

If `make up` is left as-is once T045 adds `kafka-init`, every `make up` will report failure after a
completely successful startup — and Scenario 4's ten-cycle loop will break on cycle one.

A fix was found and verified: when **another service** declares

```yaml
depends_on:
  init:
    condition: service_completed_successfully
```

Compose understands the exit as expected and `--wait` returns 0. The same probe that failed above
returns success with that one edge added. No service in the current file consumes topics, so T045
will need to either introduce that consumer or run the provisioner outside the `--wait` set.

Recording it here so T045 starts from a known constraint rather than rediscovering it as a
mysterious failing `make up`.

---

## In one line

Four targets, and the value is in their flags: `--wait` makes `make up` mean "ready" rather than
"started", `-v` makes `make down` a genuine reset, `verify` keeps the build from touching
`~/.m2`, and `.PHONY` stops a stray `build/` directory from silently disabling the build.
