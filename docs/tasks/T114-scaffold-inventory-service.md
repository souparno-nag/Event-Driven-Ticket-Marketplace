# T114 — Scaffolding the `inventory-service` module

**What this task did:** generated a new Spring Boot project from start.spring.io, unpacked it into
the repository as `inventory-service/`, and removed the parts a module inside a multi-module build
must not carry.

This is the same job T057 did for `order-service`, so the shape will look familiar. What is worth
reading here is the parts that came out *differently* this time.

---

## Why generate it instead of writing it

A Spring Boot project has a fair amount of boilerplate: a build file listing the right starter
libraries, a class with a `main` method and the annotation that switches Boot on, a resources
folder, a test folder. [start.spring.io](https://start.spring.io) — the "Spring Initializr" — is the
official tool that produces all of that from a form.

Writing it by hand is possible but pointless, and it is how a module ends up subtly different from
its siblings: a missing `<properties>` block here, a different directory name there.

### Who ran it, and why that is worth a note

This task is marked **[human]** in `tasks.md`. The project's constitution (Principle V) says an AI
assistant must not, on its own initiative, download and install things onto your machine — fetching
a generated archive from a website is exactly that, and the reason for the rule is that downloads
and installs reach outside the repository in ways a code diff cannot show you.

For this task you explicitly asked me to run it for you. That is the rule working as intended rather
than being broken: the point of Principle V is that *you* decide, not that the step is physically
forbidden. It is recorded here so the deviation is visible later, instead of looking like the rule
was quietly ignored.

---

## What was asked for

| Field | Value | Why |
|---|---|---|
| Project | Maven | Matches the rest of the repository |
| Group | `com.marketplace` | Same group as `common-events` and `order-service`, so the modules are siblings |
| Artifact | `inventory-service` | Becomes the directory name and the jar name |
| Package | `com.marketplace.inventory` | Where the Java code lives |
| Java | 21 | Records and pattern matching are used throughout the project |

And eight dependencies:

| Dependency | What it is for in this service |
|---|---|
| **Spring Web** | Not for an API — this service exposes none. It is what Actuator needs to serve `/actuator/health` over HTTP |
| **Spring Data JPA** | Talking to PostgreSQL through Java objects instead of hand-written SQL |
| **PostgreSQL Driver** | The actual database connection |
| **Spring Data Redis** | *New in this module.* Redis is where seat holds live — the fast store that arbitrates who gets a seat |
| **Spring for Apache Kafka** | This is the project's first message **consumer**. Everything before now only published |
| **Flyway Migration** | Versioned database migrations, so a fresh checkout builds the schema itself |
| **Actuator** | Health and metrics endpoints |
| **Validation** | Rejecting malformed input |

The package name is worth one sentence. If you leave the field blank, Initializr derives it from the
artifact id — and `inventory-service` is not a legal Java package name, so it substitutes an
underscore and you get `com.marketplace.inventory_service`. That is what happened to `order-service`
in T057 and had to be renamed afterwards. Typing the package in explicitly avoided the repeat.

---

## The Spring Boot version problem

Here is the interesting part.

This project runs on **Spring Boot 3.3.13**, pinned once in the root `pom.xml`. The version was
chosen deliberately: Spring Cloud 2023.0.x — which supplies Eureka and the API Gateway in build
step 7 — is a *verified* pairing with Boot 3.3, and no such verified pairing exists for Boot 4.x.
Picking a Boot version is really picking a whole set of libraries that were tested together.

But start.spring.io only offers versions that are currently supported, and 3.3 has aged out. The
oldest it will now generate is 4.0.8:

```text
4.2.0 (SNAPSHOT)   4.2.0 (M1)   4.1.2 (SNAPSHOT)   4.1.1   4.0.9 (SNAPSHOT)   4.0.8
```

So the generated `pom.xml` says Boot 4.0.8, which is *not* the version this project uses.

That is fine, and it is exactly what happened with `order-service`. The generated pom is a starting
point, not a finished file. T115 repoints it at this project's own parent, which is where the real
Boot version comes from. **4.0.8 was chosen specifically because it is the same version
`order-service` was generated at** — that means the corrections T115 has to make are the identical
set of corrections already documented in `order-service/pom.xml`, rather than a new and slightly
different set nobody has seen before.

The pom is committed in this task **exactly as Initializr produced it**, wrong Boot version and all.
Splitting "get the file" from "fix the file" into two commits means the diff in T115 shows precisely
what had to change and why — which is the more useful thing to be able to read back later.

---

## What was deleted, and why

Generated code is a starting point. Six things that came back do not belong in a module inside this
repository.

### The Maven wrapper — `mvnw`, `mvnw.cmd`, `.mvn/`

A wrapper is a small script plus a config file that downloads a specific Maven version and runs it,
so everyone building the project uses the same Maven whatever they happen to have installed. That is
genuinely useful, and this repository already has one at the root.

A second wrapper *inside* a module is a second opinion about which Maven version to use. The day the
two disagree, the build behaves differently depending on which directory you were standing in when
you ran it. Deleted.

### `.gitignore` and `.gitattributes`

The root `.gitignore` already covers `target/` for every module, and the root `.gitattributes`
already sets line-ending handling. Two files saying the same thing is two files that can drift
apart. Deleted.

### `HELP.md`

A links page Initializr adds for people meeting Spring Boot for the first time. It describes the
generated project, not this one. Deleted.

### The placeholder test

Initializr generates one test that starts the application and asserts nothing:

```java
@SpringBootTest
class InventoryServiceApplicationTests {
	@Test
	void contextLoads() { }
}
```

It looks harmless, but with Data JPA, Redis and Kafka on the classpath, "the application starts" now
means "it found a working database, a working Redis and a working broker". There are none of those
configured yet, so this test would fail the build from the moment the module joins it — not because
anything is wrong, but because it is asking for infrastructure that arrives later.

A real version of this test comes in T138 and T139, backed by throwaway PostgreSQL, Redis and Kafka
containers that the test starts for itself. Deleted for now.

---

## What is left

```text
inventory-service/
├── pom.xml                                                  (as generated — T115 adapts it)
└── src/
    ├── main/java/com/marketplace/inventory/InventoryServiceApplication.java
    └── main/resources/application.properties                (T117 replaces this with application.yml)
```

Three files. `InventoryServiceApplication.java` stays: unlike the repository root — which is an
*aggregator*, a build file that produces no code of its own — this module really is a runnable
application, and that class with its `main` method is what runs.

Nothing builds yet. The module is not registered in the root `pom.xml` until T116, so as far as
Maven is concerned this directory does not exist. That is the correct intermediate state.
