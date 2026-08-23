# T056 — Walking the Definition of Done

**What this task did:** went through all fourteen Definition-of-Done items in `quickstart.md` and
established each one against evidence rather than memory. **All fourteen now tick.** Walking it
found three problems, which is the reason a final checklist is worth having at all.

---

## The measurement that was still outstanding

Thirteen items were already established by earlier tasks. One was not, because it is the only item
that asserts what the environment **actually consumes** rather than what it declares:

> `core` profile starts within ~1.1 GiB, confirmed via `docker stats`

```
NAME       MEM USAGE / LIMIT   MEM %
kafka      332.5MiB / 768MiB   43.30%
postgres   23.02MiB / 256MiB    8.99%
redis        3.66MiB / 96MiB    3.81%
```

**359 MiB actually used against a 1.1 GiB budget.** Comfortably inside, and nothing close to its cap.

### Reading that honestly

The temptation is to conclude the limits are three times too generous and could be cut. **That would
be the wrong lesson from an idle measurement**, and worth stating plainly:

- **PostgreSQL and Redis hold nothing.** At build step 1 no service writes to either. PostgreSQL is
  running an empty database; Redis has no seat locks because there is no inventory service. Their
  23 MiB and 3.7 MiB are the cost of the processes existing, not of doing work. From step 2 onward
  both grow.
- **Kafka at 43% is the only meaningful number**, and it is meaningful because Kafka *is* doing
  something — it holds fourteen channels with three partitions each, and a JVM whose heap is pinned
  at 512 MiB. 332 MiB is that JVM at rest.
- **A limit is a ceiling, not a forecast.** Its job is to stop one component dragging the host into
  swap under load (R10), and load is exactly what has not happened yet. The load test in build step
  9 puts 1000 virtual users through this, and that is the measurement that would justify changing a
  number.

So the item ticks on the claim it actually makes — the environment starts within its stated budget —
and the headroom is recorded as headroom rather than as waste.

---

## The three problems walking it found

A checklist that ticks cleanly on the first pass usually means it was not read carefully. This one
did not.

### 1. `kafka-init` was missing `mem_reservation`

The item says *every* service declares `mem_limit`, `mem_reservation`, and `memswap_limit`. Checked
mechanically across all seven services, and the topic provisioner declared only two of the three —
it was added in T045 and the third setting was simply forgotten.

Fixed. A job that lives two seconds will never contend for memory, so the practical effect is nil,
but **a rule with one silent exception is a rule nobody can check.** The next person verifying this
gets a clean yes rather than a judgement call about whether the exception was intentional.

### 2. The heap item had gone stale

> every JVM service pins its heap to roughly 60–70% of its cap

Kafka is 67% and Zipkin is 65%, but **Elasticsearch is 42%** — because T042 raised its cap from 1 GiB
to 1.5 GiB after it was OOM-killed with a 640 MiB heap.

Two bad options and one good one. Ticking it anyway would record a claim contradicted by the file.
"Fixing" Elasticsearch back into the 60–70% band would reintroduce the crash the change existed to
prevent.

So the **checklist item was amended** to state the rule the system actually follows — pin the heap
explicitly rather than accept the JVM's 25% default, at a fraction that leaves room for off-heap
memory — with Elasticsearch's 42% named as the deliberate exception and the reason attached. Editing
a specification item is worth doing loudly rather than quietly; the alternative was a tick that
looked fine and meant nothing.

### 3. `quickstart.md` still described seven components

The Eureka deferral had been reflected in `infra/README.md` during T041 and T042, but not here:

- the profile table said `full | All seven | ~3 GiB` — it is six, at 3.1 GiB
- the port map listed Eureka on 8761 with no marker

Two documents describing the same environment differently is worse than either being wrong alone,
because a reader who notices cannot tell which to trust. Both corrected, with Eureka kept in the
port map as *"not yet — build step 7"* rather than deleted, since the port is still reserved.

---

## The gap that is recorded rather than ticked

**FR-011 is satisfied for six of its seven components.** It requires a message broker, in-memory
store, relational database, search index, **service registry**, tracing collector, and metrics
collector. The registry is deferred to build step 7.

There is no Definition-of-Done item for it, so ticking all fourteen does not overstate anything —
but closing a feature with a requirement partly unmet and saying nothing would be the kind of
omission that is technically defensible and practically misleading. A **Known gap at completion**
note now sits directly above the checklist, with the reasoning and a pointer to
`docs/tasks/T033-eureka-deferred.md`.

T033 stays unchecked in `tasks.md` on purpose. It is the only marker inside the task list pointing
at work that outlived this feature.

---

## The fourteen, and where each was established

| # | Item | Established by |
|---|---|---|
| 1 | Seven contracts as records, no framework dependency | T019–T025, T028; `dependency:tree` re-checked in T048 |
| 2 | Round-trip passes for all seven, unknown-field included | T015–T016 — 12 tests |
| 3 | No field ambiguous between message and show identity | T018 `NamingConventionTest`; every field re-read in T052 |
| 4 | `SeatsReserved.lockExpiresAt` strictly after `occurredAt` | T020, `ValidationTest$LockExpiry` |
| 5 | `make up` healthy in under 5 min, verified under `full` | T042 — 10.98s for six components |
| 6 | `make health` per component, derived from the profile | T040; re-observed in T047 |
| 7 | All three memory settings; JVM heaps pinned | **this task** — one gap fixed, wording amended |
| 8 | `core` within ~1.1 GiB via `docker stats` | **this task** — 359 MiB |
| 9 | Ten teardown/restart cycles | T043 — 10/10 |
| 10 | Fourteen channels, 3 partitions, idempotent | T047 |
| 11 | Ordering across 100 concurrent orders | T048 — 500 messages, 8 threads |
| 12 | `./mvnw clean verify` from a clean checkout | T050 — also with an empty Maven cache |
| 13 | `infra/README.md` documents profiles, memory, ports | T041 |
| 14 | Schemas match records field-for-field | T052 — one drift found and fixed |

Two of the fourteen were settled by this task. The other twelve were settled when the work was done,
which is what made this a walk rather than a scramble.

---

## In one line

Fourteen items, all ticking — after a missing memory setting, a stale heap rule, and a document
still describing seven components were found by actually checking rather than remembering.
