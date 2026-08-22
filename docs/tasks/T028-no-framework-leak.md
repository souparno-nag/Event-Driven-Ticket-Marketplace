# T028 — Verifying no framework leaked in

**What this task did:** verified **FR-010** — the contract module depends on no application
framework. This is a check rather than a change, and it closes User Story 1.

---

## What was checked

```bash
./mvnw -pl common-events dependency:tree -Dscope=compile
```

```
com.marketplace:common-events:jar:0.0.1-SNAPSHOT
\- com.fasterxml.jackson.core:jackson-annotations:jar:2.17.3:compile
```

**One line.** No `org.springframework`, no `org.projectlombok`, nothing else.

The full tree shows everything else sitting at `test` scope, where it cannot escape:

```
+- com.fasterxml.jackson.core:jackson-annotations:jar:2.17.3:compile
+- com.fasterxml.jackson.core:jackson-databind:jar:2.17.3:test
|  \- com.fasterxml.jackson.core:jackson-core:jar:2.17.3:test
+- com.fasterxml.jackson.datatype:jackson-datatype-jsr310:jar:2.17.3:test
+- org.junit.jupiter:junit-jupiter:jar:5.10.5:test
...
\- org.assertj:assertj-core:jar:3.25.3:test
   \- net.bytebuddy:byte-buddy:jar:1.14.19:test
```

And the published jar contains exactly the thirteen classes it should:

```
7 records + 3 enums + Topics + SagaEvent + Validation = 13
```

`EventJson` is correctly **absent** — it lives in test sources (T013), so it ships to nobody.

---

## Why `-Dscope=compile` is the assertion that matters

The full tree is informative; the compile-scope tree is the actual test. Scope controls what a
module that depends on this one **inherits**:

```
order-service depends on common-events
    └─ inherits: jackson-annotations        ← compile scope propagates
       does NOT inherit: junit, assertj, databind, byte-buddy    ← test scope stops here
```

If `spring-boot-starter` were at compile scope here, every one of the six services would inherit it
whether they wanted it or not — and so would every future module. Dependencies at the bottom of a
module graph are the ones that spread furthest, which is why a shared library is exactly where you
have to be strictest.

## What FR-010 actually buys

"No framework" sounds like purity for its own sake. It is not; it has three concrete payoffs.

**Tests run with no container.** All 31 tests execute in about a quarter of a second, because there
is no application context to start. A test suite fast enough to run on every save gets run on every
save.

**Any module can depend on it.** A module with a framework dependency imposes that framework on
every consumer. This one imposes a single annotations jar — 78KB of annotation definitions with no
runtime behaviour at all.

**It cannot accumulate behaviour.** This is the subtle one. With Spring on the classpath, someone
eventually adds `@Component`, injects a repository, and the contract module quietly starts *doing*
things. Then it cannot be tested without a context, cannot be used by a module that does not want
Spring, and cannot be reasoned about as "just the message shapes". Leaving the framework out
prevents that drift structurally rather than by asking people to be disciplined.

The T006 pom made this true by construction — inheriting `spring-boot-starter-parent` for its
`dependencyManagement` (version pins only, nothing on the classpath) while declaring exactly one
compile dependency. This task confirms the construction held.

---

## An honest caveat: this is a one-time check

A manual verification rots. Nothing in the build stops someone adding `spring-boot-starter` to this
pom tomorrow, and the next person to run `dependency:tree` might be nobody.

The mechanical fix is `maven-enforcer-plugin` with a `bannedDependencies` rule, which would fail the
build on `org.springframework:*` or `org.projectlombok:*` appearing at compile scope. That is the
same instinct as T018's naming test — turn a convention into something the build enforces — and it
would be a genuine improvement.

I have not added it, because the plan scopes this task to verification and `quickstart.md` lists it
as a manual gate. Worth raising as a small follow-up rather than deciding unilaterally: the enforcer
rule is about ten lines in the root pom and would make FR-010 permanent instead of periodic.

---

## User Story 1 is complete

This closes the MVP of build step 1:

```
common-events/src/main/java/com/marketplace/events/
├── RejectionReason.java         T008
├── PaymentFailureReason.java    T009
├── CancellationReason.java      T010
├── Topics.java                  T011
├── SagaEvent.java               T012 · sealed T026 · TRADEOFF T027
├── Validation.java              T014
├── OrderCreated.java            T019
├── SeatsReserved.java           T020
├── SeatsRejected.java           T021
├── PaymentSucceeded.java        T022
├── PaymentFailed.java           T023
├── OrderConfirmed.java          T024
└── OrderCancelled.java          T025

common-events/src/test/java/com/marketplace/events/
├── EventJson.java               T013
├── ContractRoundTripTest.java   T015, T016
├── ValidationTest.java          T017
└── NamingConventionTest.java    T018
```

**31 tests, 0 failures, no infrastructure required.**

The independent test from the plan — *"`./mvnw -pl common-events test` passes with no infrastructure
running"* — holds. There is no Kafka, no Postgres, no Docker involved in any of this. That is the
point of doing contracts first: they are the artifact every later build step imports, and they were
made stable before anything depended on them.

---

## Try it yourself

```bash
./mvnw -pl common-events clean test
./mvnw -pl common-events dependency:tree -Dscope=compile
```

**Expect**: 31 tests passing, and a compile-scope tree with exactly one entry.

To see why annotations-only is such a small commitment:

```bash
ls -la ~/.m2/repository/com/fasterxml/jackson/core/jackson-annotations/2.17.3/*.jar
```

**Expect**: around 78KB. Compare `spring-boot-starter`, which pulls in tens of megabytes across
dozens of jars. That difference is what every future module inherits — or does not.

---

## What comes next

**Phase 4 (User Story 2)**: the Docker Compose environment — Kafka in KRaft mode, Postgres, Redis,
Elasticsearch, Eureka, Zipkin, and Prometheus, each with a real healthcheck and a memory limit, and
profiles so build steps 1–5 need only about 1.1 GiB rather than the full stack.

It touches no Java at all, and it is where `make up` starts meaning something.
