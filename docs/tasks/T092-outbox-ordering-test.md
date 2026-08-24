# T092 — Specifying per-order sequence, and that one poisoned row doesn't spread

**What this task did:** wrote the test for guarantee 12 — one order's messages reach the channel in
the order they were recorded, even under concurrent relaying — plus a second, closely related test
for SC-013: a parked row halts only the order it belongs to.

---

## Confirmed to fail, for the right reason

```text
OutboxOrderingIT.java:[49,17] cannot find symbol
  symbol:   class OutboxRelay
```

Same missing class, verified in isolation.

## This is mostly a test of the claim query, not of anything T099 writes

`contracts/outbox-relay.md` says this plainly: the ordering guarantee "lives in the predicate the
claim query uses to select rows," not in the relay method's own logic. The claim query (T094) is
what only ever offers the **earliest** unsent row for a given order — a later row for that order is
structurally invisible to any relay until the earlier one leaves `PENDING`. Whoever writes T099's
method body cannot break this by getting the ordering "wrong" through the method's own code; they
could only break it by doing something the contract explicitly warns against, like sending
asynchronously without waiting for each row's outcome before moving to the next.

This test exists to prove that end to end — through the real relay, against a real claim query, with
real concurrent contention — rather than by reading the query's SQL and reasoning about it in the
abstract.

## `containsExactly`, not `containsExactlyInAnyOrder`

```java
assertThat(seqList).containsExactly(0, 1, 2);
```

100 orders, three rows each, relayed by three threads racing against each other. Each row's payload
carries its own recording sequence number (`{"seq": 0}`, `{"seq": 1}`, ...), and after everything
drains, this checks that the sequence numbers for one order's key arrive **in that exact order** —
not merely that all three showed up. A relay that happened to publish an order's rows out of turn
would still pass a "did everything arrive" check; this is written specifically so that mistake fails
loudly.

## Halting one order, proven by checking three rows at once

```java
assertThat(...blocker...).isEqualTo(OutboxStatus.PARKED);
assertThat(...blockedFollower...).isEqualTo(OutboxStatus.PENDING);
assertThat(...healthy...).isEqualTo(OutboxStatus.PUBLISHED);
```

`parkedRecordHaltsItsOwnOrderButNotOthers` sets up exactly the scenario FR-030 describes: one order
with a first row aimed at a channel that doesn't exist (`no.such.channel`) and a perfectly healthy
second row behind it, alongside a completely unrelated, healthy order. After running the relay
`maxAttempts` times:

- the poisoned row parks, as guarantee 7 already proved it should;
- the row **behind** it on the same order stays `PENDING` — never sent, because sending it would mean
  publishing a later fact before an earlier one that never made it out;
- the unrelated order's row is `PUBLISHED`, completely unaffected.

All three outcomes checked together is what proves isolation specifically — a parked row that
*correctly* stops its own order but *incorrectly* also stalled the healthy one would fail only the
third assertion, and a test checking just the first two would miss it.

This test's own name is not one of the two names `contracts/outbox-relay.md` lists for guarantee 12
— the contract only names `preservesPerOrderOrder`. SC-013, the parked-row-isolation property, is a
distinct success criterion from the spec that belongs naturally alongside the ordering guarantee it
depends on, and `tasks.md`'s own description of this task asked for both. The second test earns its
place here rather than being invented.
