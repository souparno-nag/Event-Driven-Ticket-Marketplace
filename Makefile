# Entry points for the local environment and the build.
#
# WHY a Makefile rather than a handful of shell scripts: one file lists every command a developer
# needs, so `make` plus tab-completion answers "what can I run here?" without reading a README.
# There are no scripts to keep executable, no PATH assumptions, and the recipes below double as
# documentation of the exact flags each command depends on — several of which are not optional
# (see `up` in particular).
#
#   make up      start the components for the active profile and wait until they are healthy
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
.PHONY: up down build logs

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
up:
	$(COMPOSE) up -d --wait --wait-timeout 300

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
down:
	$(COMPOSE) down -v

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
