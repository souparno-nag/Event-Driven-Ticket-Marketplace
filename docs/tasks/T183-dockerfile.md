# T183 — `inventory-service/Dockerfile`

**What this did:** wrote a multi-stage Docker build for this service, mirroring order-service's own
Dockerfile exactly — and, while actually building the image rather than trusting it would work, found
and fixed a real, already-existing bug in order-service's own Dockerfile too.

---

## Why the build starts from the repository root, not from inside `inventory-service/`

This service is one module inside a multi-module Maven project, sharing a parent `pom.xml` and a
`common-events` module with everything above it. A Docker build scoped only to the
`inventory-service/` folder could never see either one — Maven needs the whole reactor (the parent pom
plus every module it lists) to resolve this service's own dependency on `common-events` at all. That's
why the build command names the repository root as its context, even though the Dockerfile itself lives
one folder down.

## Why dependency resolution and source compilation are two separate steps

The Dockerfile copies every `pom.xml` first, runs Maven just far enough to download every dependency,
and only THEN copies the actual source code. This isn't an arbitrary ordering — it's what makes an
ordinary code change (which happens constantly) reuse the already-downloaded dependencies (which
change rarely) instead of re-fetching everything from Maven Central on every single build. Docker
caches each step separately, and a step only re-runs when something it depends on has actually
changed; keeping "what packages do I need" and "what does my code look like" as separate steps is what
lets that caching actually help.

## Why the image ships an already-tested jar rather than re-testing inside the build

This service's own integration tests start real PostgreSQL, Redis, and Kafka containers of their own.
Running that inside a Docker image build would mean Docker containers spinning up more Docker
containers just to build an image — slow, and pointless, since those tests were already run (and are
expected to pass, module the one deliberate T156-style gap) before anyone ever asked for a production
image. The build step here packages the jar without re-running that suite.

## A real, already-existing bug this task's own verification caught

Building the image for real — not merely writing the Dockerfile and assuming it would work — failed
immediately with a genuinely confusing error: Maven refused to even read the project, complaining that
`order-service` (a module this build never even touches) didn't exist in the build context. The cause:
Maven needs to know about EVERY module a multi-module project's root `pom.xml` lists before it can
build any single one of them, even if only one is actually being compiled — reading the whole project
structure comes before deciding what to build, not after. This service's own Dockerfile only copied
`inventory-service`'s own `pom.xml`, leaving `order-service`'s invisible to that read.

Checking order-service's own Dockerfile for the identical mistake found it too: it only ever copied
its own pom, never inventory-service's. That Dockerfile has been broken in exactly the same way since
the moment this service was added to the project's root `pom.xml` — nobody had actually run a real
`docker build` against it since then, so the break went unnoticed until this task ran one for the first
time. Both Dockerfiles were fixed the same way: copy every module's `pom.xml`, not only the one this
particular image builds.

## Verifying it

```text
$ docker build -f inventory-service/Dockerfile -t inventory-service:verify .
...
naming to docker.io/library/inventory-service:verify done

$ docker build -f order-service/Dockerfile -t order-service:verify .
...
naming to docker.io/library/order-service:verify done
```

Both images build successfully. Running the inventory-service image directly (with no real database or
broker reachable, since it wasn't started through `docker-compose`) confirms Spring Boot itself starts
correctly — Tomcat initialises, JPA repository scanning completes, and the application reaches the
point of attempting its database connection — the correct, expected shape of "this jar is real and
runs," short of the full networked environment `make up` provides.
