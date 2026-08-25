# T113: the first Dockerfile in this project, and a real mistake it caught along the way

Every service the project brief describes is meant to have its own Dockerfile, so it can eventually
run as a container the same way `kafka`, `postgres`, and every other backing component already do.
`order-service` is the first one to actually need it.

## Why this builds from the repository root, not from `order-service/` itself

`order-service` isn't a standalone project — it's one module in a Maven **reactor**, alongside
`common-events`, tied together by the parent `pom.xml` one directory up. A Docker build context
scoped to `order-service/` alone would never be able to see either one, so this image is built with:

```bash
docker build -f order-service/Dockerfile -t order-service .
```

— note the `.` at the end: the build context is the repository root, even though the Dockerfile
itself lives inside `order-service/`. The root `.dockerignore` already anticipated this months
before this task existed, with a comment saying almost exactly this.

## The two stages

**Build stage** (`eclipse-temurin:21-jdk`): copies just the poms and the Maven wrapper first,
resolves dependencies, *then* copies source and packages the jar. Ordering it that way means an
ordinary source-code change only invalidates the layers after dependency resolution, not that slow
step itself.

**Runtime stage** (`eclipse-temurin:21-jre`): copies only the finished jar out of the build stage and
runs it. The JDK — a much bigger image, and needed only to *compile* things — never ships in the
final image at all; the JRE alone is enough to *run* an already-built jar.

## The mistake this caught: `-DskipTests` isn't what it sounds like

The first version of this Dockerfile used `-DskipTests`, the flag anyone reaching for "skip the
tests" would type first. Building the image with it took over 45 minutes and then failed outright,
timing out trying to download Testcontainers and its transitive dependencies from Maven Central.

The reason is a genuinely easy thing to miss: `-DskipTests` skips *running* tests, but Maven still
*compiles* the test source files first — and compiling them means resolving every dependency those
test files need, Testcontainers included, even though nothing in a production image build ever
executes a single test. `-Dmaven.test.skip=true` is the flag that actually means what it sounds like
here: it skips compiling test sources at all, so those test-only dependencies are never even looked
for. Once that one flag changed, the same build finished in under ten seconds.

## Verified for real, not just "it compiled"

Docker images are the kind of thing that can look finished and still not actually work — a missing
runtime dependency, a wrong port, an entrypoint that silently exits. So this was checked by actually
running the container: built the image, started it on the project's real Docker network pointed at
the real `postgres` and `kafka` containers, confirmed `/actuator/health` came back `UP`, and then sent
it a real booking request over HTTP and got back a real `202 Accepted` with a real order id — the
same request `curl` in the README's own `order-service` section sends. The test image and container
were removed afterward; nothing from this verification is wired into `infra/docker-compose.yml` yet,
which stays deferred to build step 7 on purpose (the roadmap's own open question about whether
`make up` builds project images at all is answered there, not here).
