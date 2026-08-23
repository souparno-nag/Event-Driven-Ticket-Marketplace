# T053 — The README, and the temptation to describe the wrong thing

**What this task did:** wrote the repository's `README.md` — what the project is, what you need,
how to start it, what runs under which profile, and where the specification lives.

The hardest decision in it was not what to include. It was **admitting what does not exist yet.**

---

## The status banner, and why it comes second

The obvious README for this project opens with a ticket marketplace: a saga, seven services, seat
locking, payments, compensation. All of that is designed, specified, and diagrammed — and none of it
is built. The repository contains a contract library, a build root, and a docker-compose file.

A README describing the finished system would be describing something you cannot run. Someone
clones it, follows the quickstart, and finds no `POST /orders`. They then have to work out for
themselves whether they misconfigured something or whether the document was aspirational — and the
second possibility, once suspected, contaminates everything else the document says.

So the second thing on the page is a bounded status block:

> **Status: build step 1 of 11 — the foundation.** What exists today is the shared message contract
> library, the multi-module build, and a one-command local environment. There is no booking logic
> yet.

It comes *second* rather than first because the first paragraph still has to answer "what is this?".
A reader who does not know what the project is cannot evaluate the caveat. One sentence of identity,
then the honest scope.

The roadmap table at the bottom does the same job in detail: eleven steps, one marked done, ten
marked not started. **A roadmap that marks nothing as unbuilt is a wish list.**

---

## What a README is actually for

The test applied to every section was: *does this help someone who just cloned the repository, in
the first ten minutes?*

That kept some things in and pushed others out.

**Kept — prerequisites, stated as a short list.** Exactly two: JDK 21 and Docker. And one thing that
is *not* required, which surprises people: Maven. `./mvnw` fetches the version the project expects,
which is why every command in the file is `./mvnw` and never `mvn`. This is not a guess — T050
verified it by building from a clean clone with an empty Maven cache.

**Kept — the profile table.** Six components are defined and half of them do nothing until build
step 6. Someone who does not know that runs everything and wonders why their laptop is unhappy. The
table is small and it prevents a specific bad first experience.

**Kept — the "not behind a flag" note about integration tests.** `./mvnw verify` starts a Kafka
container, and a reader who does not expect that will think the build is hanging.

**Pushed out — the design rationale.** Choreography versus orchestration, why an outbox, why a Lua
script rather than a `SETNX` loop, the read-model consistency window. All of it belongs in a README
eventually; the project brief schedules it for build step 11, when the things being justified
actually exist. Arguing for a transactional outbox in a repository that contains no outbox is
explaining a decision the reader cannot inspect.

Until then the reasoning lives in `docs/tasks/` — 52 documents at this point, one per task, each
written for someone new to the technology. The README says so rather than leaving them undiscovered.

---

## Layering, not repeating

Three documents now describe the environment, and they overlap by design rather than by accident:

| Document | Question it answers | Length |
|---|---|---|
| `README.md` | What is this and how do I start it? | one screen of commands |
| `infra/README.md` | What does it cost, what ports, why did it break? | the operator's reference |
| `docs/tasks/*.md` | Why is it built this way? | one per decision |

The root README carries the profile table because choosing a profile is a first-ten-minutes
decision. It does **not** carry the per-component memory limits, the port map, or the exit-137
diagnostic — those are the second question, and it links to them instead.

The failure mode being avoided is a README that duplicates the operator guide: two copies of the
same table, one of which is quietly wrong six months later. Every overlap here is a deliberate
summary with a link to the detail.

---

## Links, verified rather than assumed

The README references eleven paths — `infra/README.md`, `infra/.env`, the six specification
documents, `contracts/`, `CLAUDE.md`, and the constitution. Every one was checked to exist:

```
ok   CLAUDE.md
ok   infra/.env
ok   infra/README.md
ok   specs/001-event-contracts-foundation/{spec,plan,research,tasks,quickstart}.md
ok   specs/001-event-contracts-foundation/contracts/
```

Worth doing because T054 had just found the opposite: two source comments pointing at README content
that did not exist. A broken link in the first document a reader opens is worse than no link, and
checking them costs one command.

---

## In one line

A README that says what you can run today, links to the detail rather than duplicating it, and marks
ten of eleven build steps as not started — because the alternative is a document that reads as a
promise and gets discovered as fiction.
