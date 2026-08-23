# Entry points for the local environment and the build.
#
# WHY a Makefile rather than a handful of shell scripts: one file lists every command a developer
# needs, so `make` plus tab-completion answers "what can I run here?" without reading a README.
# There are no scripts to keep executable, no PATH assumptions, and the recipes below double as
# documentation of the exact flags each command depends on — several of which are not optional
# (see `up` in particular).
#
#   make up      start the components for the active profile and wait until they are healthy
#   make health  report the state of each component in the active profile, one line each
#   make down    stop everything and delete its data — the clean reset
#   make build   compile and test every module from the root
#   make logs    follow logs, optionally for one component
#
# Which components `up` starts comes from COMPOSE_PROFILES in infra/.env (T037), not from here.

# WHY -f rather than `cd infra && docker compose`: a recipe that changes directory makes every
# other path in that recipe relative to somewhere else, and `./mvnw` in particular is at the root.
# Compose resolves its project directory from the compose file's own location, so `-f` still finds
# infra/.env and still resolves the bind mount for prometheus.yml against infra/. Verified: this
# command run from the repo root returns the three `core` services, not all six.
COMPOSE := docker compose -f infra/docker-compose.yml

# WHY .PHONY: make's job is normally to build a FILE named after the target, and it skips a target
# whose file already exists and looks up to date. `build` is exactly the name that collides — the
# day a `build/` directory appears, `make build` would silently do nothing. Declaring these as
# phony says "this is a command, not a file", so they always run.
.PHONY: up health down build logs

# Set explicitly rather than relying on `up` happening to be written first. Without this line the
# behaviour of a bare `make` is decided by the ORDER of the targets below, so reordering the file
# for readability would quietly change what `make` does.
.DEFAULT_GOAL := up

# `docker compose up -d` returns as soon as the containers are CREATED, which is well before any of
# them can serve a request. Since none of the six components declares a depends_on edge (T038 — none
# has an ordering dependency), nothing else gates on readiness either, and without --wait `make up
# && make health` would report failures that are really just components still booting.
#
# --wait blocks until every started container passes the health check written for it in T029-T035,
# and exits non-zero if any of them never gets there. That is what makes `make up` a truthful signal
# and what lets Scenario 4 chain ten cycles with `||` and trust the result.
#
# The timeout encodes SC-002 — every component healthy within five minutes — instead of leaving it
# as prose in the spec. Without it a component that never becomes healthy hangs the terminal
# indefinitely; with it, that failure is reported and the shell comes back.
# The second step provisions the fourteen message channels (T044/T045). It runs AFTER --wait, so
# the broker is known to be serving before any channel is created.
#
# `run --rm` rather than leaving the job in the `up` set: a container that exits is neither running
# nor healthy, and `--wait` cannot tell "finished" from "died", so a job inside that set fails every
# `make up` on exit code 0. `run` waits for the job and PROPAGATES ITS EXIT CODE, so a failed
# provisioning still fails `make up` — verified, a job exiting 3 makes this command exit 3.
#
# The guard exists because `obs` runs without a broker. Asking `config --services` whether kafka is
# active reuses the same profile-derived list `make health` reads, rather than assuming.
up:
	$(COMPOSE) up -d --wait --wait-timeout 300
	@if $(COMPOSE) config --services | grep -qx kafka; then \
	  $(COMPOSE) run --rm kafka-init; \
	fi

# FR-016: one line per component, so an operator can see WHICH one is unhealthy without reading
# raw logs. `make up` already fails on an unhealthy environment, but it fails as a single verdict;
# this is the target that says which component to go and look at.
#
# THE THING THIS TARGET MUST NOT DO is check a hardcoded list of components. Under `core` only three
# of the six are supposed to be running, so a fixed list would report Elasticsearch, Zipkin, and
# Prometheus as failures every single time — training the reader to ignore red output, which is
# worse than having no health target at all.
#
# `config --services` is the fix, and it is exactly the right source: it returns the services the
# ACTIVE profile enables, computed by Compose from the same COMPOSE_PROFILES the `up` target obeyed.
# The list can never drift from what was started, because it is not a second list.
#
# WHY `docker inspect` rather than parsing `docker compose ps`: ps renders a human-facing status
# string ("Up 4 minutes (healthy)") whose shape is not a stable interface. inspect returns the
# state field itself. The template prefers the health status when the container declares a health
# check and falls back to the raw container state when it does not — which is what distinguishes
# "running but not yet ready" from "ready".
#
# `exited:0` is reported as `completed` rather than a failure, for the one-shot topic provisioner
# arriving in T045. A container that did its job and exited is not a broken component, and treating
# it as one would make a correct environment report a permanent failure.
health:
	@services="$$($(COMPOSE) config --services 2>/dev/null | sort)"; \
	if [ -z "$$services" ]; then \
	  echo "No components in the active profile (COMPOSE_PROFILES=$${COMPOSE_PROFILES:-unset from the environment; see infra/.env})"; \
	  exit 1; \
	fi; \
	failed=0; \
	for svc in $$services; do \
	  cid="$$($(COMPOSE) ps -aq $$svc 2>/dev/null | head -n1)"; \
	  if [ -z "$$cid" ]; then \
	    state="not created"; \
	  else \
	    state="$$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}:{{.State.ExitCode}}{{end}}' $$cid 2>/dev/null)"; \
	  fi; \
	  case "$$state" in \
	    healthy)    mark="ok  " ;; \
	    running:*)  mark="ok  "; state="running (no health check declared)" ;; \
	    exited:0)   mark="ok  "; state="completed" ;; \
	    starting)   mark="WAIT"; failed=1 ;; \
	    *)          mark="FAIL"; failed=1 ;; \
	  esac; \
	  printf '  %s  %-16s %s\n' "$$mark" "$$svc" "$$state"; \
	done; \
	if [ $$failed -ne 0 ]; then \
	  echo "  -> not ready. 'make logs SERVICE=<name>' shows why; 'make up' starts what is missing."; \
	fi; \
	exit $$failed

# -v deletes the named volumes, so this is a reset rather than a stop.
#
# TRADEOFF: the alternative is a plain `down` that keeps data, with the destructive version behind a
# separate `reset` target. Rejected because the failure it prevents is both likely and badly
# disguised: Kafka stamps its cluster id into its data directory on first start and refuses to boot
# against a directory holding a different one, so a surviving volume turns the next `up` into a
# cryptic metadata error. FR-015 and SC-005 are precisely a measurement of teardown-and-restart, and
# a reset that is only clean when you remember an extra flag is not a reset. Nothing here holds data
# worth keeping — it is generated demo state — so the usual reason to preserve volumes does not
# apply. A real deployment would invert this default.
#
# `--profile '*'` enables EVERY profile, and that asymmetry with `up` is deliberate. Compose filters
# `down` by the active profile exactly as it filters `up`, so a plain `down` under `core` removes
# the three core containers and silently leaves Zipkin, Prometheus, and Elasticsearch running.
# T042 hit precisely this: after a `full` run, `make down` reported success while two containers
# stayed up for another forty minutes, holding the project network so the next teardown failed with
# "Resource is still in use".
#
# Starting is a question of what you need; stopping is not. Teardown should always mean "leave
# nothing behind", whatever the file happened to be set to when you started. --remove-orphans
# extends that to containers whose service has since been deleted from the compose file.
down:
	$(COMPOSE) --profile '*' down -v --remove-orphans

# The root build. `verify` rather than `install`: verify compiles and runs the tests without copying
# artifacts into the developer's ~/.m2, which keeps the build from mutating machine-global state as
# a side effect of checking that the code works.
build:
	./mvnw clean verify

# Follows by default, because the reason to open logs is almost always to watch something happen.
# --tail=100 supplies just enough history to see why a container is unhealthy without replaying its
# entire startup.
#
# SERVICE is optional and unset by default, which Compose reads as "every service":
#   make logs                 all components in the active profile
#   make logs SERVICE=kafka   just that one
logs:
	$(COMPOSE) logs -f --tail=100 $(SERVICE)
