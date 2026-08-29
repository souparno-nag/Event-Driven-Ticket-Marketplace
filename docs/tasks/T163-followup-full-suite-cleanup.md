# Follow-up to T163 — two bugs a full-suite run found that individual runs never could

**What this did:** after T163 was already committed, one last full `mvn verify` across the whole
`inventory-service` module (every `*IT` class together, not one file at a time) turned up two bugs that
no single test file, run on its own, could ever have shown. Both are fixed here. Neither is the kind of
bug a code reader would spot by inspection — both only exist because of how JUnit and Testcontainers
share resources across many test classes in one run, which is exactly why "run the whole suite, not
just the tests that seem related" matters as a habit and not just a slogan.

---

## Bug 1 — closing a database connection pool only where the heaviest test lived wasn't enough

`HighConcurrencyIT` (the base class four of this service's tests use to fire a thousand requests at
once) already had `@DirtiesContext(classMode = AFTER_CLASS)` on it. In plain terms, that annotation
tells Spring: "once every test in this class has finished, throw away the whole application you built
for them — don't keep it around to hand to the next test class." Without it, Spring tries to be
helpful and reuse an already-built application (including its database connection pool) for any later
test that asks for an identical configuration, which is normally a nice speed-up.

The problem: this project runs many DIFFERENT test classes, each with its own slightly different
configuration (one talks to Kafka, one doesn't, one raises the connection pool size for heavy
concurrency, and so on). Spring caches one built application PER distinct configuration. Run the whole
suite together and, at any moment, several of these cached applications can be alive at once, each
holding open its own handful of real connections to the same PostgreSQL database. PostgreSQL itself
has a hard ceiling on how many connections it will accept in total — and running every test file
one-by-one, this ceiling was never in danger, because each run only ever had one application alive.
Running them all together in one `mvn verify`, the ceiling was hit: `FATAL: sorry, too many clients
already`, meaning PostgreSQL had refused the newest connection attempt outright.

Putting the "throw it away when you're done" annotation only on the one class using the very biggest
pool (60 connections) was not enough, because the pileup wasn't really about size — it was about
COUNT. Several other test classes, each with a small, ordinary pool of their own, were still enough
of them stacking up at once to tip PostgreSQL over its limit. The fix moves that same annotation up to
`InventoryIT`, the one class every single test in this service already extends, so that EVERY test
class throws its application away the moment it finishes — guaranteeing at most one application, and
its one pool of connections, is ever alive at a time. The cost is that Spring has to rebuild a fresh
application for every test class instead of reusing one across a few of them; at this project's size
that costs a few extra seconds total, which is a completely reasonable trade for a test suite that
does not depend on how many other classes happen to be cached in memory at the same moment.

## Bug 2 — a test that assumed it had the table to itself, when it didn't

`OutboxRelayPortIT` writes one row into the `outbox` table and then waits up to a few seconds for the
scheduled background job to send it and mark it done. Run by itself, this always passed. Run as part of
the full suite, right after `ReservationContentionIT` (the thousand-requests-at-once test), it
consistently failed — the row just sat there, never marked done, for the entire wait.

The two tests don't call each other and don't share any Java objects, so at first this looked like
nothing more than "the machine was busy from the previous test, so the timer fired late" — a
plausible-sounding but ultimately wrong guess, worth recording as a mistake avoided: widening the wait
from ten seconds to twenty (matching what order-service's own equivalent test already uses) did NOT
fix it. That result is what proved the real explanation had to be something else, rather than settling
for the first guess that sounded reasonable.

Adding a one-line diagnostic — counting how many rows were already sitting in the `outbox` table the
moment this test started — settled it directly: **19,700** leftover rows, still waiting to be sent.
The explanation: every booking attempt this service processes, successful or refused, writes one row
into this same table, and `ReservationContentionIT` alone drives a thousand attempts, twenty times
over, in one test run. All of this project's test classes share ONE real PostgreSQL database for the
whole suite (deliberately, for the same reason two tests can't each start their own database and still
prove anything about how the real one behaves) — so those 19,700 rows didn't disappear when
`ReservationContentionIT` finished; they simply sat in the shared table, waiting.

The background job that sends outbox rows is required to send them in the order they were written —
that's what stops a later fact about an order from ever being announced before an earlier one. Which
means it had no choice but to work through all 19,700 leftover rows, oldest first, before it could ever
reach the one row this test had just written moments ago. At a hundred rows sent per half-second
poll, draining that backlog takes roughly a hundred seconds — nowhere near the ten or twenty seconds
this test was willing to wait. The row was never being ignored or lost; it was standing, quite
correctly, at the back of a very long, very real line.

The fix is a single line: before this test writes its own row, it now deletes every row already in the
table. That's a fair thing for this specific test to do, because it always inserts exactly one row and
checks on that same row within the same test method — there is never a reason for a row to still be
sitting there from an earlier run of this same test. It is not, however, a fix for the underlying
habit: any other test in this service that calls the booking logic will leave its own rows behind in
this same shared table for whoever runs afterward. This test is now safe from that; nothing broader has
been changed, and that is worth someone's attention later if a similar test is added.

## Why this belongs in its own commit rather than being folded into T163

T163 was already committed, and its own recorded results were honest at the time they were written —
they came from real runs, just not yet from one single run of every test file together. These two bugs
only became visible once that combined run was finally done. Recording that discovery as its own
commit, with its own explanation, keeps the project's history matching what actually happened at each
point, rather than quietly rewriting an already-committed task's story after the fact.

## The result, run for real one more time after both fixes

```text
Tests run: 38, Failures: 29, Errors: 0, Skipped: 0
```

Zero `too many clients` errors anywhere in the run. Every one of the 29 failures is one of the same,
single, already-known cause recorded in T160 and T163's own write-ups: `lock_seats.lua` is still an
empty stub (T152), waiting on the developer exercise (T156). Nothing here is a new failure — the full
suite now runs cleanly down to exactly that one already-understood, already-flagged gap.
