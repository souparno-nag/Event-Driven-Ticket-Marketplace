# T170 — `SeatLockRebuildIT`

**What this did:** wrote the test for SC-013/SC-014 — proving that a hold recorded durably in
PostgreSQL survives Redis forgetting everything it knew, is restored before anything is allowed to
consume a booking request, and comes back with its ORIGINAL remaining lifetime rather than a fresh
full one.

---

## Why Redis is allowed to forget everything, and why that's a deliberate choice rather than a bug

`infra/docker-compose.yml` runs Redis with snapshotting turned off on purpose. Redis is meant to be
disposable here — PostgreSQL is what this service trusts as the true record of what happened, and Redis
exists purely to answer "is this seat claimed right now" fast enough to arbitrate a thousand
simultaneous buyers. The tradeoff that choice makes is that every restart genuinely loses every hold
Redis was tracking — which is fine, AS LONG AS something rebuilds Redis from PostgreSQL before the
service starts trusting it again. This test exists to prove that "as long as" actually holds.

## Why this test builds a second, completely independent application inside one test method

Every other test in this service works with a Spring application that's already fully started by the
time the test method runs — there's no "before startup finished" moment left to observe from inside it.
Proving SC-013 means watching something that only happens once, at the very beginning of a real
startup: the rebuild. So this test builds a second, brand-new Spring application from scratch, inside
the test method itself, pointed at the exact same PostgreSQL and Redis the test's own (already-running)
application already uses. The moment that second application finishes starting is the moment this
test's core assertion gets to ask its question: is the hold back yet?

## Why this couldn't just be done by adding another property override to the existing setup

This session found out, the hard way, while building an earlier heavy-concurrency test: a subclass
registering a property with the same name as one an ancestor class already registered does NOT win —
Spring keeps whichever registration happened first in the class hierarchy, no matter what a later class
tries to set. That rules out simply overriding Redis's connection details again for this one test.
Building the second application directly, with an explicit list of connection properties handed to it
by hand, sidesteps that limitation rather than fighting it.

## Why the seat's own Redis key is set once, then deliberately erased, rather than just never being set

The test intentionally puts the hold's key into Redis BEFORE wiping everything — briefly recreating
"a hold that was genuinely live right before the outage" — specifically so the subsequent `FLUSHALL`
is a faithful stand-in for what a real restart with snapshotting disabled actually does: forget
something that was really there a moment ago, not merely start from an already-empty cache.

## Why the recovered hold's remaining lifetime matters as much as whether it's there at all

A rebuild that just re-creates every hold with a fresh, full 120-second lifetime would pass a test that
only checked "does the key exist again" — and would still be a real bug: a hold that should have lapsed
in 10 seconds would instead survive another two full minutes, silently extending every in-flight
booking's own deadline on every single restart. This test checks the TTL is smaller than a full
lifetime specifically to catch that mistake, not just the more obvious one of forgetting to restore
anything at all.

## Verifying it

```text
Tests run: 1, Failures: 1, Errors: 0
```

The mechanism itself works exactly as designed: a genuine second Spring application boots successfully
against the shared containers, migrations apply, the context starts cleanly — proving the harness this
test depends on is solid. The one assertion that fails does so for the correct, expected reason: the
restored key does not exist (Redis reports `-2`, its own code for "no such key"), because
`SeatLockRebuilder` (T179) doesn't exist yet to have restored it.

## A small, necessary change to `InventoryIT` itself

`POSTGRES` and `REDIS`, the two shared containers every other test in this service already depends on,
were package-private — reachable only from classes in the same package as `InventoryIT`. This test
lives in `com.marketplace.inventory.startup`, a different package, because that is where the task
itself places it. Widening those two fields to `protected` is what lets a subclass in a different
package still read them, which is exactly what Java's own visibility rules mean `protected` for — no
other change to `InventoryIT`'s behaviour was needed or made.
