# T058 — Adapting the generated `pom.xml`

**What this task did:** replaced the generated build file's parent with this project's aggregator,
and rewrote every dependency in it — because changing the parent invalidated all of them.

---

## The one-line change that was not one line

The plan said: swap the parent, add `common-events`, done. The parent block was this:

```xml
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>4.0.8</version>
</parent>
```

### What a parent does

A Maven "parent" is a build file your build file inherits from. Two things come down from it:

- **Settings** — the Java version, how tests are run, source encoding.
- **`dependencyManagement`** — a long list of library versions. It does not put anything on your
  classpath; it just says *if* you ask for Jackson, here is the version you get. That is why you
  can write a dependency with no `<version>` and it still works.

Initializr made Spring Boot itself the parent, so this module was inheriting Boot's opinions
directly and ignoring the repository's. Pointing it at `ticket-marketplace` instead means the Boot
version, the Java version, and the test wiring are decided in exactly one file — the root `pom.xml`
— and every service module built later gets the same answers.

### Why the version has to be 3.3.13 and not 4.0.8

Initializr offers the current Spring Boot. This project is pinned to **3.3.13**, decided back in
step 1: Spring Cloud — which brings Eureka and the API Gateway in build step 7 — ships in "release
trains" that are each tested against one Boot generation. Spring Cloud 2023.0.x is a verified match
for Boot 3.3. No such verified match exists for 4.x, and a mismatch does not fail politely at build
time; it fails at startup with missing-method errors deep inside framework code.

---

## Then everything broke

Spring Boot 4 **renamed most of its starters**. The generated file was full of names that simply do
not exist in 3.3, so inheriting the older parent left Maven unable to resolve them:

| Generated (Boot 4) | Correct for Boot 3.3 |
|---|---|
| `spring-boot-starter-webmvc` | `spring-boot-starter-web` |
| `spring-boot-starter-kafka` | `org.springframework.kafka:spring-kafka` — not a Boot starter at all |
| `spring-boot-starter-flyway` | `org.flywaydb:flyway-core` |
| six `spring-boot-starter-*-test` slices | one `spring-boot-starter-test` |

This is the practical lesson of the task: pinning an old framework version is not just a number
edit. The generated file and the pinned version disagreed about what things are *called*.

### The Flyway trap

Two Flyway dependencies are declared, and the second looks redundant:

```xml
<dependency><groupId>org.flywaydb</groupId><artifactId>flyway-core</artifactId></dependency>
<dependency><groupId>org.flywaydb</groupId><artifactId>flyway-database-postgresql</artifactId></dependency>
```

Flyway 10 split every database into its own module. Without the second one the build succeeds, the
application starts, and *then* dies with `Unsupported Database: PostgreSQL 16`. A missing dependency
that only appears at runtime is worth a comment in the file, which it has.

---

## What was added

**`common-events`** — the contract module from step 1. Every message this service publishes is
defined there, and so are the channel names it publishes them to. Depending on it turns a
mistyped topic name into a compile error, instead of messages written into a channel nobody reads
while every service cheerfully reports healthy.

**Tracing, in two halves that are easy to confuse:**

- `micrometer-tracing-bridge-brave` **creates** the trace and passes it along.
- `zipkin-reporter-brave` **sends** finished traces to Zipkin for viewing.

Only the first is load-bearing here. This service stores a trace identifier on every outbox row so
the background relay can continue the original request's trace minutes later — that works whether
or not Zipkin is running. The reporter is only what makes the result visible in a browser.

**`micrometer-registry-prometheus`** — turns the metrics Actuator collects into the text format
Prometheus scrapes.

**Testcontainers, for PostgreSQL and Kafka** — starts real databases and real brokers in Docker for
the duration of a test, then throws them away. The usual shortcut is an in-memory database like H2,
and it was rejected deliberately: everything this service needs to be trusted about is behaviour the
real products provide and the imitations do not — row-level locking with `FOR UPDATE SKIP LOCKED`,
partial indexes, the `jsonb` column type, and Kafka's ordering guarantees. Testing against H2 would
answer questions about H2.

**Awaitility** — the relay runs on a timer, so its effects appear *after* the call that caused them
returns. Awaitility retries an assertion until it passes or a timeout expires. The alternative is
`Thread.sleep`, which is either too short and fails randomly, or long enough to be safe and makes
the whole suite crawl.

---

## What stayed put

`spring-boot-maven-plugin` remains in *this* file and is deliberately absent from the root. It
repackages the jar into a single executable file with a special classloader — right for an
application you run, wrong for a library like `common-events` that other modules depend on. Each
service opts in for itself.

The `<properties>` block was deleted. The Java version now comes from the root. Repeating it here is
how two modules quietly end up compiled against different Java versions.
