# T043 — Ten teardown-and-restart cycles (Scenario 4, SC-005, FR-015)

**What this task did:** ran `make down` followed by `make up` ten consecutive times under the
`core` profile, checking that every cycle reached full health. **Result: 10/10 passed.**

```
cycle 1 healthy in 6s      cycle 6  healthy in 7s
cycle 2 healthy in 7s      cycle 7  healthy in 8s
cycle 3 healthy in 7s      cycle 8  healthy in 7s
cycle 4 healthy in 7s      cycle 9  healthy in 7s
cycle 5 healthy in 7s      cycle 10 healthy in 7s
```

Six to eight seconds per cycle, with a spread of two seconds across ten runs.

---

## What this test is actually looking for

"It works on my machine" is usually true the first time. The interesting question is whether it
works the *eleventh* time, on a machine that has already run it ten times — because the thing that
breaks reproducibility is **state left behind by the previous run**.

That failure mode is nasty for a specific reason: it does not appear during development. You build
the environment, it works, you commit. The failure surfaces days later, or on a colleague's machine,
as an error that describes a symptom rather than a cause.

The concrete hazard here is Kafka. KRaft stamps a **cluster id** into its data directory on first
start, and refuses to start against a directory holding a different one. If a volume survives a
teardown while the cluster id changes, the next startup fails with a metadata mismatch that never
uses the words "stale data". The compose file guards this from both ends — a fixed `CLUSTER_ID`
rather than a generated one (T029), and `make down -v` deleting the volumes (T039) — and this test
is what confirms the guard holds under repetition rather than in theory.

Ten cycles is not an arbitrary number: it is enough that an intermittent leak would very likely show
up at least once, and the consistency of the timings is itself evidence. A cycle that leaked state
would tend to get slower as data accumulated, or fail outright. Six to eight seconds every time,
with no drift across ten runs, is the shape of a genuinely clean reset.

---

## Why the cycles take seven seconds when the first startup took seven minutes

The first `make up` took **7m34s**, and almost all of that was pulling ~590 MiB of images. Once the
images are in the local cache, starting the same three containers takes seconds:

```
✔ Container kafka     Healthy   6.7s
✔ Container postgres  Healthy   6.2s
✔ Container redis     Healthy   6.2s
```

Worth separating those two numbers, because they answer different questions. "How long does a clean
checkout take?" is a download problem and depends on the network. "How long does a restart take?" is
a startup problem and depends on the components. Only the second is a property of the environment's
design, and it is the one this test measures.

The three start in parallel and all report healthy within about a second of each other, which is
what having no `depends_on` edges buys (T038): nothing waits its turn.

---

## The honest caveats

**These cycles ran under `core`, not `full`.** Three components, not six. Elasticsearch — the
slowest to start and the one that failed in T042 — is not covered by this result. `core` is the
right choice for the repetition test (it is what build steps 1–5 actually use, and ten `full` cycles
would take an hour), but the claim being recorded is specifically about the `core` environment.

**Two containers from the earlier `full` attempt were still running during these cycles.** Zipkin
and Prometheus survived `make down` because of the teardown defect T042 uncovered — `down` filtered
by the active profile and removed only the three `core` containers. The cycles were unaffected,
since those two share nothing with Kafka, PostgreSQL, or Redis, but the run was not conducted on a
completely empty machine and saying otherwise would be overstating it.

**The teardown was fixed after this test ran.** `make down` now passes `--profile '*'`, so it
removes every container regardless of the active profile. Under a pure `core` run the two commands
behave identically — there is nothing outside `core` to remove — so this result stands, but it was
produced by the earlier, narrower `down`.

---

## What it demonstrates

- **SC-005**: ten consecutive teardown-and-restart cycles all reaching a healthy state. ✅
- **FR-015**: startup is repeatable, and a teardown-and-restart reaches the same healthy state with
  no manual cleanup. ✅ Nothing was cleaned by hand between cycles.

The environment is reproducible rather than incidentally working — which is the distinction the
success criterion was written to force.
