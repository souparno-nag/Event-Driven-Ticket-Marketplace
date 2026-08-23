# T059 — Registering the module in the build root

**What this task did:** added one line to the repository's root `pom.xml`.

```xml
<modules>
    <module>common-events</module>
    <module>order-service</module>    <!-- this line -->
</modules>
```

That is the whole change. It is worth its own task because of what it proves.

---

## What "registering" means

The root `pom.xml` is an **aggregator**. It compiles nothing itself; its job is to list the modules
that make up the project and hold the settings they share. When you run `./mvnw verify` at the root,
Maven reads that list, works out which module depends on which, and builds them in the right order.

Before this line, `./mvnw verify` built `common-events` and nothing else — `order-service/` was a
directory of files that happened to be sitting in the repository, invisible to the build. After it,
one command at the root compiles both modules and runs both sets of tests.

## Maven works out the order itself

You do not tell Maven that `common-events` must be built before `order-service`. It reads
`order-service/pom.xml`, sees a dependency on `com.marketplace:common-events`, notices that this is
one of its own modules rather than something to download, and orders the build accordingly. This is
called the **reactor**.

The practical consequence is that the two modules stay honest with each other. Change a field on a
message record in `common-events` and the next root build fails in `order-service` immediately —
rather than at runtime, in a different service, on a message that no longer deserializes.

---

## Why this is a task rather than a footnote

Back in step 1 the plan made a promise: adding a service later should cost **one line in the root
plus that module's own build file, and nothing else**. No restructuring, no editing existing
modules, no touching `common-events`.

This task is where that promise gets tested for the first time, and it held. `common-events` was not
opened. The root's shared test configuration — the split between fast unit tests and slower
integration tests — was not touched, and `order-service` inherits it automatically.

Five more services follow the same path: inventory, payment, projection, the gateway, and auth. Each
should cost exactly this much.
