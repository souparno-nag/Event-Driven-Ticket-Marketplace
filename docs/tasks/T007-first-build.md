# T007 — The first successful build

**What this task did:** ran `./mvnw clean verify` and confirmed the whole structure holds together
before any real code exists.

---

## Why build something empty?

There is genuinely nothing to compile yet. So what is the point?

Because it verifies the **plumbing**, at the one moment when nothing else can be blamed:

- The Maven Wrapper downloads and runs
- The aggregator finds its module
- The Spring Boot parent resolves from the internet
- Dependency versions resolve without conflict
- The module packages into a jar

If any of that is broken, you want to find out now — not in three tasks' time when there is also
new Java code, and you cannot tell whether the failure is your code or the setup.

This is why the plan puts a verification step at the end of every phase.

---

## Reading the output

```
[INFO] Reactor Summary for ticket-marketplace 0.0.1-SNAPSHOT:
[INFO] ticket-marketplace ......... SUCCESS [  0.851 s]
[INFO] common-events .............. SUCCESS [ 24.563 s]
```

Maven calls the set of modules it is building the **reactor**. Two entries appear, which confirms
the aggregator and the module found each other — the root lists `common-events` in `<modules>`, and
the module names the root as its `<parent>`. Break either side and one of these lines disappears.

The root finishes in under a second because it produces nothing; it exists only to coordinate.

### The warning is expected

```
[WARNING] JAR will be empty - no content was marked for inclusion!
```

Maven is pointing out that it packaged a jar containing no classes. Correct — `src/main/java` holds
only a `.gitkeep` placeholder. This warning disappears once the first real record is written.

```
[INFO] No tests to run.
```

Same story. `src/test/java` is empty too.

---

## The dependency tree is the real result

```
com.marketplace:common-events:jar:0.0.1-SNAPSHOT
+- com.fasterxml.jackson.core:jackson-annotations:jar:2.17.3:compile
+- com.fasterxml.jackson.core:jackson-databind:jar:2.17.3:test
|  \- com.fasterxml.jackson.core:jackson-core:jar:2.17.3:test
+- com.fasterxml.jackson.datatype:jackson-datatype-jsr310:jar:2.17.3:test
+- org.junit.jupiter:junit-jupiter:jar:5.10.5:test
|  +- org.junit.jupiter:junit-jupiter-api:jar:5.10.5:test
|  ...
\- org.assertj:assertj-core:jar:3.25.3:test
```

Three things this proves, each of which was only an argument until now.

### 1. Exactly one library ships with this module

Only `jackson-annotations` ends in `:compile`. Every other line ends in `:test`.

That means a service depending on `common-events` receives the annotations and nothing else. The
JSON engine, JUnit, and AssertJ stay behind — they existed only so this module's own tests could
run. This is the dependency-scope rule from T006, visible in practice.

### 2. Version numbers we never wrote

`2.17.3`, `5.10.5`, `3.25.3`. Search the project — those numbers appear in no file.

They came from Spring Boot 3.3.13's curated list, inherited through the aggregator. This is the
whole benefit of that parent: Spring Boot tested these versions together, so they are known to
cooperate, and a Jackson upgrade later is one line in the root rather than an edit in every module.

### 3. No Spring on the classpath

There is no line beginning `org.springframework`.

This was the concern that made the module hand-written rather than generated. Inheriting Spring
Boot's parent gives **version pinning**, not **libraries** — and the tree is the proof. Requirement
FR-010 holds, demonstrably.

### Reading the shape

Indentation means "brought in by the line above". `jackson-core` sits under `jackson-databind`
because databind needs it — you never asked for it directly. That is a **transitive** dependency.

Notice it is also marked `:test`. Scope propagates downward: a test-scoped library's own
dependencies are test-scoped too. That is why one `test` declaration cannot leak a whole subtree
into what consumers receive.

---

## What appeared on disk

```
common-events/target/common-events-0.0.1-SNAPSHOT.jar
```

`target/` is where Maven puts everything it generates. It is in `.gitignore` — running `git status`
after the build still reports a clean tree, which is `.gitignore` doing its job on a directory that
did not exist when it was written.

`clean` at the start of the command deletes this folder first, so every build begins from nothing.
Slightly slower, but it rules out the class of bug where a stale file from a previous build makes
something appear to work.

---

## Phase 1 is complete

| Task | Result |
|---|---|
| T001 | Directory skeleton |
| T002 | Base project generated from start.spring.io |
| T003 | Root POM reshaped into an aggregator |
| T004 | Maven Wrapper at the root, pinning 3.9.16 |
| T005 | `.gitignore` |
| T006 | `common-events` module POM |
| T007 | Build verified |

The foundation is proven. Phase 2 writes the first real Java: the enums and shared types every
message record depends on.
