# T057 — Scaffolding the `order-service` module

**What this task did:** generated a new Spring Boot project from start.spring.io, unpacked it into
the repository as `order-service/`, and tidied away the parts a module inside a multi-module build
must not carry.

---

## Why generate it instead of writing it

A Spring Boot project has a fair amount of boilerplate: a build file listing the right starter
libraries, a class with a `main` method and the annotation that switches Boot on, a resources
folder, a test folder. [start.spring.io](https://start.spring.io) — the "Spring Initializr" — is
the official tool that produces all of that from a form.

It is also the reason this task is marked **[human]**. The project's governing rules say an AI
assistant must not download and install things onto your machine on its own; fetching a generated
archive is exactly that. So you ran it, and I worked with what came back.

---

## What was asked for

| Field | Value | Why |
|---|---|---|
| Project | Maven | Matches the rest of the repository |
| Group | `com.marketplace` | Same group as `common-events`, so the modules are siblings |
| Artifact | `order-service` | Becomes the directory name and the jar name |
| Package | `com.marketplace.orders` | Where the Java code lives |
| Java | 21 | Records and pattern matching are used throughout |

And seven dependencies: **Web** (serve HTTP), **Data JPA** (talk to the database through objects),
**PostgreSQL Driver**, **Kafka**, **Flyway** (versioned database migrations), **Actuator**
(health and metrics endpoints), **Validation** (reject malformed requests).

---

## Three things that had to be fixed afterwards

Generated code is a starting point, not a finished module. Three parts of what came back were
wrong for this repository.

### 1. The package was `com.marketplace.order_service`

Initializr builds the package name from the artifact id when you do not type one in, and
`order-service` is not a legal Java package name, so it substituted an underscore. Every plan and
contract document in this project says `com.marketplace.orders`, and an underscore in a Java
package is unconventional enough to look like a mistake forever after. Renamed.

### 2. It brought its own Maven wrapper

`mvnw`, `mvnw.cmd` and `.mvn/` are a small script plus a config file that download a specific
Maven version and run it — so that everyone building the project uses the same Maven, whatever they
happen to have installed. That is genuinely useful, and this repository already has one at the root.

A second wrapper *inside* a module is a second opinion about which Maven version to use. The day
the two disagree, the build behaves differently depending on which directory you were standing in
when you ran it. Deleted.

### 3. It brought its own `.gitignore` and a `HELP.md`

The root `.gitignore` already covers `target/` for every module. `HELP.md` is a links page
Initializr adds for newcomers to Spring Boot. Neither earns its place. Deleted, along with a
`.gitattributes` that duplicated the root one.

The generated placeholder test was removed too. It does nothing but check that the application
starts — and because Data JPA is on the classpath, "starts" now means "found a working database",
which there is not one of yet. A real version of that test arrives in T071 backed by a throwaway
PostgreSQL container.

---

## What is left

```text
order-service/
├── pom.xml                                              (as generated — see T058)
└── src/
    ├── main/java/com/marketplace/orders/OrderServiceApplication.java
    └── main/resources/application.properties
```

`OrderServiceApplication.java` stays. Unlike the repository root — which is an *aggregator*, a
build file that produces no code — this module really is a runnable application, and that class
with its `main` method is what runs.

The `pom.xml` is committed exactly as Initializr produced it, including a Spring Boot version this
project does not use. Adapting it is T058's job, and it turns out to be a larger job than it looks.
