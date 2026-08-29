# T118 — Verifying the module joins the build

**What this task did:** ran `./mvnw -q -pl inventory-service -am verify` and confirmed it succeeds,
closing out Phase 1 of this build step.

This is a short task on purpose. It is the checkpoint the plan calls for after Setup: "the module
compiles as part of the root build, no behaviour yet" — and the value of a checkpoint is in actually
stopping to look, not in doing more work at it.

---

## What the command asks for, piece by piece

```bash
./mvnw -q -pl inventory-service -am verify
```

- **`-pl inventory-service`** — "project list": only build this module, not the whole reactor.
- **`-am`** — "also make": additionally build whatever this module depends on, so the command does
  not fail looking for a `common-events` jar that was never produced.
- **`verify`** — the Maven lifecycle phase that runs both unit tests (Surefire) and integration tests
  (Failsafe), one step past `test`. Running `verify` rather than just `compile` means this checkpoint
  is honest about testing, not only about syntax.
- **`-q`** — quiet: only warnings and errors print, so a clean pass looks clean.

### What actually got built

```text
[INFO] Building ticket-marketplace 0.0.1-SNAPSHOT       [1/3]
[INFO] Building common-events 0.0.1-SNAPSHOT            [2/3]
[INFO] Building inventory-service 0.0.1-SNAPSHOT        [3/3]
[INFO] BUILD SUCCESS
```

Three modules, not four. `-am` correctly determined that `inventory-service` depends on
`common-events` and nothing else — `order-service` was left alone entirely, which is exactly the
isolation a well-formed module dependency graph should give you. Asking Maven "what does this one
module need" and getting back a small, correct answer is itself a small piece of evidence that T115's
dependency list was written honestly rather than copied wholesale.

---

## What a passing result here does and does not mean

**It means**: the adapted pom (T115) resolves correctly, the module is registered (T116) so Maven can
find it at all, `application.yml` (T117) is syntactically valid, and the one Java file that exists —
`InventoryServiceApplication.java` — compiles.

**It does not mean**: the service can actually start. Nobody has tried running it, and trying would
fail immediately — there is no `db/migration` SQL yet, so Flyway would find nothing to apply against
a schema that does not exist; Redis and Kafka connection settings exist in the file but nothing in the
module has attempted to open either connection.

That gap is intentional and it is why `verify` rather than `spring-boot:run` is the right check at
this point. `common-events` produced `OrderingGuaranteeIT`'s test output during this build (visible in
the log) because `inventory-service` still declares a compile-time dependency on it, but
`inventory-service` itself contributed zero tests to the run — the placeholder was deleted in T114
specifically because it would have failed here, and nothing has replaced it yet. **Zero tests passing
is the correct outcome for this checkpoint**, not a gap to be worried about. The first tests that mean
anything arrive with the schema and the entities in Phase 2.

---

## The wider check: the whole suite, not just the new module

Per the project's stated practice ("run the whole suite before calling a task done, not only the
tests that look topically relevant"), the full multi-module build was also run:

```text
ticket-marketplace ...... SUCCESS [  0.275 s]
common-events ........... SUCCESS [  7.127 s]
order-service ........... SUCCESS [01:23 min]
inventory-service ....... SUCCESS [  0.061 s]
BUILD SUCCESS — Total time: 01:30 min
```

This matters more here than it might for an ordinary code change, because T117 touched a
configuration file and T116 touched the root pom — both are the kind of change where a mistake would
most plausibly show up as *someone else's* build breaking, not this module's own. It did not: every
module still passes independently.

---

## Where this leaves the module

Phase 1 (Setup) is complete: `inventory-service` exists, compiles, is part of the root build, and its
configuration file names every property later code will read. Nothing in it does anything yet — no
table, no entity, no listener.

Phase 2 (Foundational) is next: the four Flyway migrations, the JPA entities they back, the ported
outbox, and the shared test infrastructure that every one of the three user stories in this step
depends on. Nothing in Phase 3 through 5 can begin until Phase 2 is done.
