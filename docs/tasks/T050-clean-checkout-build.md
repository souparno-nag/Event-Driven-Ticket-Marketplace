# T050 — Scenario 7: does it build for somebody who is not me? (SC-004)

**What this task did:** cloned the repository to a fresh directory and ran `./mvnw clean verify`
there — twice, at two different levels of strictness. **Both passed.**

| Level | What it isolates | Result |
|---|---|---|
| Clean checkout, existing `~/.m2` | uncommitted files | ✅ 39 tests, 8.8s |
| Clean checkout, **empty** local Maven repository | pre-installed dependencies | ✅ 39 tests, 45.6s, 74 MB downloaded |

---

## Why this cannot be checked in the working directory

Running `./mvnw clean verify` where the work happened proves almost nothing about SC-004. The
working directory has advantages a new contributor does not:

- **Files that exist but were never committed.** The build would use them happily. Nobody else would
  have them. This is the single most common way a project builds for its author and nobody else.
- **A warm `~/.m2`.** Every dependency is already there, including any that was installed by hand at
  some point and is no longer available from any repository the build declares.
- **Leftover `target/` output.** A stale compiled class can satisfy something the sources no longer
  produce.

`clean` removes only the third. The other two need a different starting point, so the check has to
begin with `git clone`.

---

## Level 1 — the clean checkout

```bash
git clone . /tmp/.../clean-checkout
cd /tmp/.../clean-checkout && ./mvnw clean verify
```

```
[INFO] Tests run: 34, Failures: 0, Errors: 0, Skipped: 0        <- surefire, unit
[INFO] Tests run: 5,  Failures: 0, Errors: 0, Skipped: 0        <- failsafe, integration
[INFO] Reactor Summary:
[INFO] ticket-marketplace .......... SUCCESS [ 0.287 s]
[INFO] common-events ............... SUCCESS [ 7.525 s]
[INFO] BUILD SUCCESS

real 0m8.801s
```

Three things were confirmed about the clone itself before trusting the result:

- **`infra/.env` came across.** T037 argued this file must be committed despite `.gitignore`'s
  default-deny on `.env`, and relied on a `!infra/.env` exception. This is the check that the
  exception actually works for someone who was not there when it was written — without it, `make up`
  on a fresh clone would start nothing.
- **The executable bit on `create-topics.sh` survived.** Git tracks the execute permission, and
  losing it would break provisioning in a way that looks nothing like a permissions problem.
- **No `target/` directory existed anywhere**, so nothing could be satisfied by stale output.

---

## Level 2 — the empty Maven repository

Level 1 still resolves dependencies from `~/.m2`, which has accumulated everything this machine has
ever built. SC-004 says "with zero manual dependency installation beyond the documented
prerequisites", and that claim is about **dependencies**, not just files. So:

```bash
./mvnw clean verify -Dmaven.repo.local=/tmp/.../m2-empty
```

An empty directory as the local repository means every artifact must be fetched from the
repositories the build declares. Anything installed by hand, or inherited from an unrelated project,
is simply not there.

```
BUILD SUCCESS
real 0m45.641s
```

**88 jars, 74 MB**, all pulled from Maven Central. The extra 37 seconds is entirely download time —
Spring Boot's dependency-management BOM, Jackson, JUnit, AssertJ, Testcontainers, the Kafka client,
and the Maven plugins themselves.

Nothing had to be installed first, and the phrase "documented prerequisites" is doing very little
work: a JDK and Docker. Not even Maven — `./mvnw`, the Maven Wrapper, downloads the exact Maven
version the project expects, which is why the command is `./mvnw` and never `mvn`.

---

## What the run actually covers now

It is worth noticing how much stronger this became in the last two tasks. Before T049, `verify`
would have run 34 unit tests. Now it also starts a real Kafka broker and proves the per-order
ordering guarantee under 100 concurrent orders:

```
[INFO] --- failsafe:3.2.5:integration-test (default) @ common-events ---
[INFO] Running com.marketplace.events.OrderingGuaranteeIT
[INFO] Tests run: 5, Failures: 0 ... Time elapsed: 5.096 s
[INFO] --- failsafe:3.2.5:verify (default) @ common-events ---
```

That is the payoff for rejecting the opt-in profile in T049. "One command builds and tests
everything" is only a meaningful claim when everything is behind that command.

---

## One thing this does NOT yet demonstrate

SC-004's wording is "builds and tests all modules **in dependency order**". With a single module,
dependency ordering is not really exercised — the reactor lists the aggregator and then
`common-events`, but there is no second module that could have been built in the wrong sequence.

So what is proved today is "builds and tests all modules". The ordering half becomes a real claim
at build step 2, when `order-service` depends on `common-events` and the reactor has to work out
that `common-events` must be built first regardless of the order the `<modules>` list happens to
give. Saying this now is cheaper than discovering later that a checkmark covered less than it
looked like.

---

## What it demonstrates

- **SC-004**: the full project builds and its tests pass from a single root command on a clean
  checkout, with zero manual dependency installation. ✅ Verified at both levels.
- **FR-018**: one root command compiles and tests all registered modules. ✅

---

## In one line

Cloned it fresh, built it with an empty dependency cache, and it passed — so "it works on my
machine" is now a statement about the repository rather than about this machine.
