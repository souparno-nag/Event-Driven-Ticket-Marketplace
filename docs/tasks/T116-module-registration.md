# T116 — Registering `inventory-service` in the build

**What this task did:** added one line to the root `pom.xml` so Maven knows the module exists.

```diff
  <modules>
    <module>common-events</module>
    <module>order-service</module>
+   <module>inventory-service</module>
  </modules>
```

That is the whole change. It is worth a page anyway, because *why* it is only one line is the thing
the previous two tasks were arranging.

---

## What `<modules>` actually does

The root `pom.xml` is an **aggregator**. It has `<packaging>pom</packaging>` and produces no code of
its own; its job is to name the modules that make up the project and hold the settings they share.

When you run `./mvnw verify` at the root, Maven does not simply walk the list top to bottom. It reads
every listed module's pom, works out which ones depend on which, and builds them in **dependency
order** — a calculation Maven calls the *reactor*.

You can see it choose:

```text
[INFO] Building ticket-marketplace 0.0.1-SNAPSHOT     [1/4]
[INFO] Building common-events 0.0.1-SNAPSHOT          [2/4]
[INFO] Building order-service 0.0.1-SNAPSHOT          [3/4]
[INFO] Building inventory-service 0.0.1-SNAPSHOT      [4/4]
```

`common-events` goes second because both services depend on it, not because it is written second in
the file. If you shuffled the three lines the build order would be identical. The list is a *set of
members*, not a sequence — a comment now records that, because "the order looks meaningful" is an
easy wrong assumption to form. It is kept in build-step order regardless, so it reads as a history of
how the project grew.

---

## Two directions of connection, and why both are needed

This is the part that is genuinely confusing the first time.

```text
        root pom.xml
        ├── <modules> ──────────────►  "these directories are part of my build"    (T116, this task)
        └── ◄──────── <parent> ──────  "I take my versions and settings from you"  (T115)
```

They are separate links and neither implies the other:

- A module with a `<parent>` pointing at the root but **not** listed in `<modules>` inherits
  everything correctly — and is never built, because nothing at the root mentions it. This was the
  state after T115. `./mvnw verify` succeeded and quietly did nothing with `inventory-service/`.
- A module listed in `<modules>` but **without** the parent link would get built, but would decide
  its own Java version, its own library versions and its own test wiring — which is precisely the
  drift the single root pom exists to prevent.

So T115 pointed the child up, and T116 points the parent down. Now the module is both governed and
built.

---

## Verifying it

The full suite, at the root, across all four modules:

```text
ticket-marketplace ...... SUCCESS [  0.229 s]
common-events ........... SUCCESS [  6.735 s]     34 unit + 5 integration tests
order-service ........... SUCCESS [01:22 min]      9 unit + 32 integration tests
inventory-service ....... SUCCESS [  0.054 s]      no tests yet
BUILD SUCCESS
```

80 tests, none failing.

Two things about that output are worth noticing.

**`order-service` was re-run, not just `inventory-service`.** That is deliberate. This task changed a
file every module inherits from, so "did my change break something" is a question about the whole
build, not about the module being added. Running only the tests that look topically relevant is how a
regression in a sibling module gets discovered a week later.

**`inventory-service` finishes in 0.054 seconds** because there is nothing in it yet — one
application class, no tests. Its jar is built and the module is a full member of the reactor, which
is all this task claims. The first real thing it does arrives in Phase 2.

---

## Where this leaves the module

```text
inventory-service/
├── pom.xml                                                  ✔ adapted (T115), registered (T116)
└── src/
    ├── main/java/com/marketplace/inventory/InventoryServiceApplication.java
    └── main/resources/application.properties                ← still the generated placeholder
```

The service does not *run* yet, and would fail immediately if you tried: `application.properties`
carries one line naming the application, and with JPA, Redis and Kafka on the classpath, Spring Boot
starts up looking for a database, a Redis and a broker it has been told nothing about.

Giving it that configuration is **T117** — the datasource, the dedicated `inventory` PostgreSQL
schema, the Redis connection, the Kafka consumer group, and the setting that stops the message
listeners from starting on their own so the seat-hold rebuild can go first. **T118** then verifies the
whole thing boots.

Phase 1 is three tasks down and two to go.
