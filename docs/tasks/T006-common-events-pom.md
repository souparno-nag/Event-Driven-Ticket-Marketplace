# T006 — The `common-events` module build file

**What this task did:** wrote `common-events/pom.xml` by hand, giving the shared message module
its own build definition.

---

## Two `pom.xml` files now

| File | Role |
|---|---|
| `pom.xml` (root) | The **aggregator**. Lists modules and shared settings. Produces no code. |
| `common-events/pom.xml` | A **module**. Produces an actual jar of compiled classes. |

The module file says "my parent is the aggregator", and the aggregator says "one of my modules is
`common-events`". They point at each other, and that two-way link is what lets a single
`./mvnw clean verify` at the root build everything.

---

## Dependency scopes — the main idea here

A dependency is a library your code uses. **Scope** controls *when* it is available, and this is
where most of the thinking in this file went.

```xml
<dependency>
  <groupId>com.fasterxml.jackson.core</groupId>
  <artifactId>jackson-annotations</artifactId>
</dependency>                                  <!-- no scope = "compile", the default -->

<dependency>
  <groupId>org.junit.jupiter</groupId>
  <artifactId>junit-jupiter</artifactId>
  <scope>test</scope>
</dependency>
```

| Scope | Available when | Passed on to modules that depend on this one? |
|---|---|---|
| `compile` (default) | Always | **Yes** |
| `test` | Only while compiling and running tests | **No** |

That last column is the important one. When `order-service` later depends on `common-events`, it
inherits everything at `compile` scope — but nothing at `test` scope.

This is what lets the round-trip tests use a full JSON library without forcing that library on
every service in the project.

---

## Why only `jackson-annotations` at compile scope

Jackson is the library that converts Java objects to JSON and back. It ships in pieces:

| Piece | What it is |
|---|---|
| `jackson-annotations` | Just the annotations — labels like `@JsonProperty`. No logic. |
| `jackson-databind` | The actual engine that reads and writes JSON. |
| `jackson-datatype-jsr310` | An add-on teaching the engine about `java.time` types. |

Only the **annotations** are at compile scope. The engine is test-only.

The reasoning: this module's job is to *describe the shape* of a message, not to decide how it gets
written to the wire. Each service already configures its own JSON engine — with its own settings
for date formats, unknown fields, and so on. If `common-events` forced `databind` on everyone, it
would be making a decision that belongs to them.

The annotations are safe to share because they carry no behaviour. They are labels the engine reads
*if* it is present.

---

## The apparent contradiction, resolved

The plan insists `common-events` must contain **no Spring**. Yet its parent chain leads to
`spring-boot-starter-parent`. Does Spring sneak in?

No — and it comes back to two XML sections that sound alike:

| Section | Effect |
|---|---|
| `<dependencies>` | **Adds** a library. It lands on the classpath. |
| `<dependencyManagement>` | **Only pins a version** to use *if* something asks for that library. Adds nothing. |

Spring Boot's parent is almost entirely `dependencyManagement` — a curated list of ~400 libraries
and versions known to work together.

Notice what is *missing* from the dependency declarations above: no `<version>` tags. That is the
inheritance working. We ask for `jackson-annotations`, and the parent supplies the version Spring
Boot 3.3.13 was tested against. Same for JUnit and AssertJ.

So we get consistent, compatible versions for free, while the classpath contains only what we
explicitly asked for. No Spring.

That is also why the *next* task's verification includes checking the dependency tree — it proves
this reasoning holds in practice rather than only in theory.

---

## What is deliberately absent

**No `<build>` section, and no `spring-boot-maven-plugin`.** That plugin repackages a jar into a
runnable "fat jar" with all its libraries bundled inside and a special loader. Correct for an
application; wrong for a library, because the rearranged layout confuses the normal class lookup of
anything depending on it.

**No `<version>` on this module.** It inherits `0.0.1-SNAPSHOT` from the parent, so every module in
the project moves version together and they cannot drift apart.

**No `jackson-databind` at compile scope**, as explained above.

---

## Try it yourself

The project should now build for the first time. From the repository root:

```bash
./mvnw clean verify
```

This is the next task, T007, so the full explanation of what each phase does lives there. Expect
the first run to take a few minutes — it downloads Maven 3.9.16 and every dependency listed above.

To see the version inheritance in action afterwards:

```bash
./mvnw -pl common-events dependency:tree
```

**Expect**: `jackson-annotations` with a concrete version number that we never wrote down, and no
line beginning `org.springframework`.
