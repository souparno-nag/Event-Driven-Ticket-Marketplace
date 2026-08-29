# T115 — Adapting `inventory-service/pom.xml`

**What this task did:** turned the file Initializr generated into one that belongs in this
repository. That means repointing it at this project's parent, undoing a set of renames Spring Boot 4
made, and adding the seven or so libraries the plan calls for that no Initializr checkbox offers.

T114 committed the generated file untouched precisely so this diff would be readable. It is worth
reading — the change is larger than "swap a version number", and the reason why is the interesting
part.

---

## 1. The parent

```diff
- <parent>
-   <groupId>org.springframework.boot</groupId>
-   <artifactId>spring-boot-starter-parent</artifactId>
-   <version>4.0.8.RELEASE</version>
- </parent>
+ <parent>
+   <groupId>com.marketplace</groupId>
+   <artifactId>ticket-marketplace</artifactId>
+   <version>0.0.1-SNAPSHOT</version>
+ </parent>
```

### What a Maven parent actually gives you

A parent pom is inheritance for build files. A child gets the parent's `<properties>`, its
`<dependencyManagement>` (a big table of "if you ask for library X, here is the version you get"),
and its `<build>` plugin configuration — without repeating any of it.

`spring-boot-starter-parent` is Spring's own version of that table: roughly 400 libraries, all
version-pinned to combinations the Spring team tested together. It is why you can write a dependency
with no `<version>` tag and still get a sane answer.

### Why swapping it is the first move

The generated pom inherited Boot **directly**, which means it decides its own Boot version — 4.0.8 —
and knows nothing about this project's. This repository has exactly one place where the Boot version
is chosen: the root `pom.xml`, which sets 3.3.13 and explains why (Spring Cloud 2023.0.x, arriving in
build step 7, is a *verified* pairing with Boot 3.3; nothing equivalent is verified for 4.x).

Inheriting the aggregator instead of Boot means the Boot version, the Java version, and the
Surefire/Failsafe test wiring are decided once and every module gets the same answer. Two modules
disagreeing about a framework version is the kind of bug that surfaces as a `NoSuchMethodError` at
runtime and takes an afternoon to trace.

Note there is no `<relativePath>` tag. Maven's default is `../pom.xml`, which is exactly where the
root pom sits, so the default is right.

---

## 2. Deleting what is now inherited

```diff
- <groupId>com.marketplace</groupId>
- <version>0.0.1-SNAPSHOT</version>
- <properties>
-   <java.version>21</java.version>
- </properties>
- <url/>
- <licenses><license/></licenses>
- <developers><developer/></developers>
- <scm>...</scm>
```

`groupId` and `version` come from the parent — a module in a multi-module build shares both with its
siblings by default, and repeating them is how a module quietly ends up on its own version.

`java.version` is inherited too. Repeating it here is how two modules quietly end up compiling
against different Java versions, and the symptom appears in whichever one you *didn't* change.

The empty `<url/>`, `<licenses/>`, `<developers/>` and `<scm/>` blocks are Initializr scaffolding for
a project that might be published to a public repository. This one is published nowhere. Removed
rather than filled in with fiction.

---

## 3. The renames — the part that is genuinely surprising

Boot 4 reorganised its starter names. Swapping the parent back to a 3.3-based one means every
renamed dependency has to be renamed back, or the build fails with "artifact not found" for names
that do not exist in 3.3.

| Generated (Boot 4) | Rewritten to (Boot 3.3) | Note |
|---|---|---|
| `spring-boot-starter-webmvc` | `spring-boot-starter-web` | Straight rename |
| `spring-boot-starter-kafka` | `org.springframework.kafka:spring-kafka` | **3.3 has no Kafka starter at all** — you depend on Spring Kafka itself. Its version is still managed by the parent |
| `spring-boot-starter-flyway` | `org.flywaydb:flyway-core` | Same idea: no Flyway starter on 3.3 |
| seven `*-test` starters | one `spring-boot-starter-test` | See below |
| `spring-boot-starter-data-redis` | *(unchanged)* | One of the few names that survived |
| `spring-boot-starter-data-jpa` | *(unchanged)* | |
| `spring-boot-starter-validation` | *(unchanged)* | |
| `spring-boot-starter-actuator` | *(unchanged)* | |

### The seven test starters

Boot 4 splits test support per slice. The generated pom carried
`spring-boot-starter-webmvc-test`, `-data-jpa-test`, `-data-redis-test`, `-kafka-test`,
`-flyway-test`, `-validation-test` and `-actuator-test` — one for each production dependency.

On 3.3 there is a single `spring-boot-starter-test` carrying JUnit 5, AssertJ, Mockito and the
Spring test context, and all seven collapse into it.

### One dependency that had to be *added* because of a rename

`flyway-database-postgresql` was already in the generated pom, but `flyway-core` was not — it arrived
transitively through `spring-boot-starter-flyway`. Removing the starter removes the transitive route,
so the core artifact now has to be named explicitly.

And `flyway-database-postgresql` is not optional despite looking like an extra. Flyway 10 moved every
database into its own module; without it Flyway starts and then fails at runtime with
`Unsupported Database: PostgreSQL 16` — a message that reads like a version-compatibility problem
rather than a missing jar.

---

## 4. What was added

Initializr has no checkbox for any of these.

### `common-events` — the contract module

Every message this service consumes or produces is defined there. This module is the project's first
**consumer**, and that makes the dependency buy something new: `OrderCreated` deserializes into a
record whose accessors are `showId()` and `messageId()`, two separate fields that happen to have the
same type.

The seat-hold key must be built from `showId` and never from `messageId` — a key built from message
identity is unique per delivery, so a redelivered request would contend with nothing and take a
second hold on a seat it already holds. Reading fields by name out of a `Map` would make that
mistake invisible. Reading them off a typed record makes the wrong one a compile error.

### Tracing: three artifacts, two jobs

- `micrometer-tracing-bridge-brave` — **creates** spans and propagates the W3C `traceparent` header
- `zipkin-reporter-brave` — **ships** finished spans to Zipkin so you can look at them
- `brave-propagation-tracecontext` — the W3C format implementation

The third is pinned to `0.2.0` by hand because Spring Boot's table does not manage a version for it.
It was already arriving transitively; it is declared explicitly because `TracingConfig` imports its
classes directly, and a class you import should come from a dependency you asked for.

For this module tracing is load-bearing in a way it was not for `order-service`. SC-015 asks for
**one** connected trace covering order-service accepting the booking, its relay publishing, this
service deciding, and this service's relay publishing. That only holds if the `traceparent`
order-service stored on its outbox row survives the trip through Kafka and is picked up here.

### `micrometer-registry-prometheus`

Turns the actuator's metrics into the text format Prometheus scrapes (FR-045).

### `lombok`, marked `optional`

Generates the getters and setters so the entity classes are readable. Deliberately only `@Getter` and
`@Setter` — never `@Data`, `@EqualsAndHashCode` or `@ToString` on a JPA entity, because the generated
`equals`/`hashCode` fold in the database identifier, so an entity's hash code *changes* after it is
saved and silently corrupts any hash-based collection already holding it.

`optional` means Lombok does not leak onto the classpath of anything depending on this module. It is
a compile-time code generator; nothing needs it at runtime.

### Test-scope: Testcontainers and Awaitility

**Testcontainers** starts real Docker containers for a test and throws them away afterwards. The
alternative — an in-memory fake database or a fake broker — is wrong here for a specific reason:
everything this service must be *trusted* about is behaviour the real products provide and fakes do
not. Script atomicity in Redis is the property under test; a Java stand-in for Redis would answer
questions about the stand-in's own locking. The partial unique index behind SC-017 is a real
PostgreSQL feature with no equivalent in H2.

There is a detail worth knowing here: **there is no official Testcontainers module for Redis.**
Postgres and Kafka each have one (`org.testcontainers:postgresql`, `org.testcontainers:kafka`), but
Redis is run with a plain `GenericContainer` pointed at the `redis:7-alpine` image — the same image
`infra/docker-compose.yml` uses. `GenericContainer` lives in `testcontainers-core`, which arrives
transitively with the other two modules.

`org.testcontainers:testcontainers` (that core artifact) is nonetheless declared explicitly, on the
same principle as `brave-propagation-tracecontext`: the test base class in T138 imports
`GenericContainer` by name, and a dependency you import from should be one you asked for rather than
one that happened to come along. This is one artifact beyond the literal list in the task, added
deliberately and for that stated reason.

**Awaitility** polls for a condition with a timeout. Both the outbox relay and the lapsed-reservation
sweeper are scheduled, so their effects arrive some time after the call that triggered them returns —
and a Redis hold lapses on a timer that signals nothing at all. The alternative is `Thread.sleep`,
which is either too short and flaky or too long and slow.

---

## 5. What stayed

`spring-boot-maven-plugin`, in this module's own `<build>` section rather than at the root.

The plugin repackages the jar into an executable "fat jar" with a nested classloader. That is correct
for a service you run, and wrong for a library like `common-events` that other modules put on their
classpath. So each service module opts in for itself, and the root never imposes it.

---

## Verifying it

Dependency resolution succeeds and everything lands on the project's own versions:

```text
com.marketplace:common-events:jar:0.0.1-SNAPSHOT:compile
spring-boot-starter-web:jar:3.3.13:compile
spring-boot-starter-data-redis:jar:3.3.13:compile
io.lettuce:lettuce-core:jar:6.3.2.RELEASE:compile
org.springframework.kafka:spring-kafka:jar:3.2.10:compile
org.flywaydb:flyway-core:jar:10.10.0:compile
org.flywaydb:flyway-database-postgresql:jar:10.10.0:compile
org.testcontainers:*:jar:1.19.8:test
```

3.3.13 throughout, matching `order-service` exactly — which is the whole point of moving the version
decision into the root pom.

The module still is not part of the build; nothing at the root mentions it. That is T116, and it is
one line.
