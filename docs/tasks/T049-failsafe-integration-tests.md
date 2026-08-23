# T049 — Two kinds of test, two plugins

**What this task did:** declared `maven-failsafe-plugin` in the build root so `OrderingGuaranteeIT`
runs during `verify`, separately from the unit tests that run during `test`.

```
./mvnw test      → 34 unit tests            (no Docker, ~1s)
./mvnw verify    → 34 unit + 5 integration  (Docker, ~9s)
```

---

## Why two plugins instead of one

Maven's convention is that Surefire runs unit tests and Failsafe runs integration tests. It looks
like duplication until you notice the plugins differ in the thing that matters: **how they fail**.

Consider what an integration test does. It starts a Kafka broker, runs assertions, and shuts the
broker down. Now suppose an assertion fails.

- **Surefire fails the build immediately.** The remaining lifecycle phases never run. Whatever the
  test started is still running.
- **Failsafe records the failure and carries on** to `post-integration-test`, which is where
  teardown belongs, and only *then* fails the build during the `verify` goal.

So the split is not bookkeeping — it is the difference between a failing test that cleans up after
itself and one that leaves infrastructure behind. Running integration tests under Surefire means a
red build can also leave you with orphaned containers, and the second problem is discovered much
later than the first.

This was checked rather than assumed. Breaking an assertion deliberately produced:

```
[INFO] --- failsafe:3.2.5:integration-test (default) @ common-events ---
[ERROR] Tests run: 5, Failures: 1 ...
[INFO] --- failsafe:3.2.5:verify (default) @ common-events ---
[INFO] BUILD FAILURE
[ERROR] Failed to execute goal ... failsafe:3.2.5:verify (default): There are test failures.
```

Read the order: the tests ran, the *next* goal still executed, and the build failed at `verify`.
Exactly the sequence the split exists to produce.

---

## The naming convention is the entire interface

Failsafe matches `**/IT*.java`, `**/*IT.java`, and `**/*ITCase.java`. Surefire matches `**/*Test.java`
and friends. Nothing lists which class is which:

- `TopicNameDriftTest` → unit test → runs in `test`
- `OrderingGuaranteeIT` → integration test → runs in `verify`

Rename a class and you change how it is run. No registry, no annotation, nothing to keep in sync —
which matters because a list of integration tests is exactly the kind of thing that silently misses
the one added last week.

---

## The trap: a plugin that is configured but never runs

Spring Boot's parent already manages the Failsafe **version**, so adding the plugin without an
`<executions>` block looks complete and does nothing at all:

```xml
<executions>
  <execution>
    <goals>
      <goal>integration-test</goal>
      <goal>verify</goal>
    </goals>
  </execution>
</executions>
```

Version management pins *which* version would be used if the plugin ran. Only a goal binding
attaches it to the lifecycle. Without those four lines, `./mvnw verify` succeeds while running zero
integration tests — a green build that tested less than it appeared to, which is worse than a red
one.

Both goals are needed and they do different jobs: `integration-test` runs the tests, `verify` is what
turns a recorded failure into a failed build. Binding only the first would run the tests and ignore
their results entirely.

---

## Declared at the build root

Both plugins go in the root `pom.xml`, not in `common-events`. The Surefire configuration added in
T048 was moved up here as part of the same change, so there is one place that describes how tests
run.

That is what keeps **SC-008** true — adding a module should require one `<module>` line in the root
and that module's own `pom.xml`, nothing else. If test wiring lived in each module, every one of the
seven services still to come would need its own copy, and they would drift.

`common-events/pom.xml` now has no `<build>` section at all.

---

## The tradeoff, named

**`./mvnw verify` — and therefore `make build` — now requires a working Docker engine**, and takes a
few seconds longer while a broker starts.

The alternative was putting integration tests behind an opt-in Maven profile, so the default build
stays fast and Docker-free. It was rejected:

- **A test nobody runs by default is a test that rots.** It breaks silently, and the breakage is
  found by whoever eventually remembers the flag — usually while debugging something else.
- **SC-004 asks that one root command builds and tests everything.** A concurrency guarantee checked
  only on request is not really being checked.
- The cost is small and honest: about eight seconds, on a project that already requires Docker to
  run at all.

If this were a codebase where the integration suite took twenty minutes, the answer would flip, and
the profile would be right. It takes five seconds.

---

## Verified

| Command | Expected | Result |
|---|---|---|
| `./mvnw clean test` | 34 unit tests, no IT, no Docker | ✅ |
| `./mvnw clean verify` | 34 unit + 5 integration | ✅ 8.7s total |
| Broken IT assertion | build fails at `failsafe:verify` | ✅ |
| After a failed run | no containers left behind | ✅ |

On that last row, one detail worth stating accurately: immediately after the failing build a
`testcontainers-ryuk` container was still running. That is not a leak — Ryuk is Testcontainers' own
reaper, deliberately outliving the JVM so it can clean up whatever the test created, then exiting on
its own. A moment later `docker ps -a` was empty. Worth knowing, because seeing it and assuming a
leak would send you looking for a problem that is actually the solution.

---

## In one line

Unit tests run in `test` and integration tests in `verify`, split by filename alone, because the two
plugins fail differently — and only one of them lets a failing test tear down what it started.
