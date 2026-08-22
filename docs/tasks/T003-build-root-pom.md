# T003 — Turning the generated project into a build root

**What this task did:** took the project you downloaded from start.spring.io and reshaped it into
an *aggregator* — a build file that coordinates several modules instead of being an app itself.

---

## What is a `pom.xml`?

It stands for **P**roject **O**bject **M**odel. It is the file Maven reads to understand your
project: what it is called, what libraries it needs, what Java version to use, and which other
modules to build.

If you have used Node.js, it fills the same role as `package.json`. It is XML rather than JSON,
which is more verbose, but the idea is identical.

---

## The change: `jar` becomes `pom`

Initializr assumed we wanted a single runnable application, so it produced a project with
`<packaging>jar</packaging>` — meaning "build this into one runnable file".

But this project is **seven modules**, not one program. So the root becomes:

```xml
<packaging>pom</packaging>
```

That tells Maven: *this file produces no code of its own. It exists to list other modules and
hold the settings they share.*

Think of it as a table of contents rather than a chapter.

```xml
<modules>
  <module>common-events</module>
</modules>
```

Right now only one module is listed. As services get built, each is added as one more line. Then
`./mvnw clean verify` at the root builds them all, in the right order, in one command.

---

## Three things were deleted, and why

### 1. The application class

Initializr generated `TicketMarketplaceApplication.java` with a `main` method and
`@SpringBootApplication`. That is the entry point for a runnable app — an aggregator has no entry
point, because it does not run. Deleted.

### 2. The Spring Boot dependencies

The generated file had:

```xml
<dependencies>
  <dependency>spring-boot-starter</dependency>
  <dependency>spring-boot-starter-test</dependency>
</dependencies>
```

Here is the important bit: **anything in `<dependencies>` at the root is inherited by every
module underneath it.**

We do not want that. `common-events` is deliberately built with no Spring in it, so any service
can use it freely without dragging Spring along. Leaving these here would silently push Spring
into it. Deleted.

### 3. The Spring Boot Maven plugin

```xml
<plugin>spring-boot-maven-plugin</plugin>
```

This one is the subtle trap. That plugin **repackages** your compiled code into a "fat jar" — a
single file containing your code *plus every library it needs*, arranged in a special layout with
a custom loader so it can be run with `java -jar`.

That is exactly right for an application you launch. It is wrong for a **library** that other
modules depend on, because the repackaged layout puts classes where Maven's normal lookup does not
expect them. Depend on a repackaged jar and you get confusing "class not found" errors.

Each *service* will declare this plugin for itself later. It just must not sit at the root where
everything inherits it.

---

## The version change: 4.0.8 → 3.3.13

The download came with Spring Boot 4.0.8, because start.spring.io only offers versions currently
receiving support. We changed it to **3.3.13**.

```xml
<parent>
  <groupId>org.springframework.boot</groupId>
  <artifactId>spring-boot-starter-parent</artifactId>
  <version>3.3.13</version>
</parent>
```

**Why go backwards on purpose?**

This project uses Spring Cloud — a companion set of libraries providing service discovery
(Eureka) and an API gateway, both arriving at build step 7. Spring Cloud ships in "release
trains", and **each train only works with a specific Spring Boot generation.** Spring Cloud
2023.0.x is a well-documented, known-good match for Boot 3.3.

Pairing the wrong versions does not produce a clear error message. It produces runtime failures
like `NoSuchMethodError` deep inside framework code, which are genuinely unpleasant to diagnose.

The cost is that Boot 3.3 no longer receives free security patches. That matters for something
exposed to the internet. This runs only on your laptop, so the practical risk is nil. `3.3.13` is
the last 3.3 release, confirmed by checking Maven Central directly rather than guessing.

---

## The part that sounds contradictory

We just said `common-events` must contain no Spring. Yet the root still says:

```xml
<parent>spring-boot-starter-parent</parent>
```

...and every module inherits from the root. So does `common-events` get Spring after all?

**No** — and the reason is worth understanding, because it trips up a lot of people.

There are two different XML sections that look similar:

| Section | What it does |
|---|---|
| `<dependencies>` | **Adds a library** to your project. It ends up on the classpath. |
| `<dependencyManagement>` | **Only states which version to use** *if* something asks for that library. Adds nothing. |

`spring-boot-starter-parent` contributes almost entirely `<dependencyManagement>`. It is a
curated list of roughly 400 libraries and the versions known to work together — Jackson, JUnit,
AssertJ, the Kafka client, and so on.

So inheriting it means: "when I ask for Jackson, give me the version Spring Boot tested with."
It does **not** mean "give me Spring." Nothing lands on the classpath until a module explicitly
asks in its own `<dependencies>`.

That is why `common-events` can inherit this parent, get correct and consistent library versions
for free, and still contain zero Spring classes. The benefit without the baggage.

---

## How to check this worked

The root `pom.xml` now exists. It cannot be built yet — `common-events` is listed as a module but
has no `pom.xml` of its own, and the `mvnw` command has not been moved to the root. Those are the
next two tasks.

You can confirm the shape:

```bash
grep -E '<packaging>|<module>|<version>' pom.xml | head
```

Expect `<packaging>pom</packaging>`, `<module>common-events</module>`, and parent version
`3.3.13`.
