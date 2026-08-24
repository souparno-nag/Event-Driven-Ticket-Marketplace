# T093 — Proving recovery needs no code, and confirming it against a real timeout

**What this task did:** wrote the test for FR-019 / SC-005 — outstanding rows get sent automatically
after a restart, with no manual step — and it is the second file in this batch that compiles and
actually runs today, before `OutboxRelay` exists at all.

---

## Why this file doesn't need `OutboxRelay`, and what that means

Every other test in this batch autowires `OutboxRelay` directly and calls `pollAndPublish()` itself.
This one doesn't reference `OutboxRelay` anywhere. That is not an oversight — it is the whole point
of the test.

"Restart recovery" sounds like a feature that needs its own code: some mechanism that notices the
service just started and goes looking for work a previous instance left behind. It doesn't need one.
The relay has no memory of its own between runs — every single execution asks the database the exact
same question, "what is `PENDING` right now?", and that question's answer does not care whether the
service asking it has been running for three hours or was started ten seconds ago. Rows inserted in
this test, before anything in it ever touches a relay, are — from the relay's point of view —
indistinguishable from rows a previous, now-dead instance of the service left behind. Recovery falls
out of the design for free; there is nothing separate to build or to test as its own mechanism.

## Confirmed: infrastructure works, only the relay is missing

```text
Tests run: 1, Failures: 0, Errors: 1
org.awaitility.core.ConditionTimeoutException:
  expected: PUBLISHED
   but was: PENDING
```

Ran this in isolation against real Testcontainers-managed PostgreSQL and Kafka, waiting up to 20
seconds. It timed out — and that is exactly right, since nothing in the application yet schedules
anything (`@EnableScheduling` and the relay bean both arrive in T097). The failure is precise and
honest: every one of the five rows sat at `PENDING` the whole time, because nothing was running to
change that. Nothing crashed, nothing hung past its bound, and the message names exactly what never
happened. This is the same "compiles today, fails cleanly until the implementation lands" pattern
`OrderApiIT` demonstrated in the previous batch — confirmed here to work correctly against real
infrastructure rather than assumed.

## Why `Awaitility` rather than a direct call

```java
Awaitility.await()
        .atMost(Duration.ofSeconds(20))
        .pollInterval(Duration.ofMillis(250))
        .untilAsserted(() -> { ... });
```

Every other test in this batch calls `pollAndPublish()` directly, because those tests are about
*what the method does* when it runs. This one is deliberately different: calling the method directly
here would be testing "does the method work," which every other test already covers, not "does
something run it **automatically**," which is what SC-005 actually asks for. `Awaitility` polls the
database state repeatedly within a bounded window — the same 20-second neighbourhood SC-005 sets —
without this test ever touching the mechanism that is supposed to be doing the work on its own.
