# T094 — `claimBatch`, the query that makes the hard guarantee free

**What this task did:** added the one method `OutboxRepository` was deliberately left without —
`claimBatch(int limit)` — a hand-written native SQL query that hands the relay a set of rows it owns
exclusively, in exactly the order they must be published.

---

## The conflict this query resolves

Two requirements pull against each other, and reconciling them is most of what makes this build step
hard:

- **Exclusivity** (FR-012): two relays must never send the same row. The standard PostgreSQL answer
  is `SELECT ... FOR UPDATE SKIP LOCKED` — lock the rows you're taking, and let anyone else just skip
  past what's already locked rather than wait for it.
- **Order** (FR-014): messages for one order must reach the channel in the order they were recorded.

Naively combining them breaks the second one. If relay A claims row 1 of an order and relay B claims
row 2 of the *same* order in the same moment, `SKIP LOCKED` has done its job — no row was claimed
twice — but nothing stops B from finishing first, and now a later fact reaches consumers before an
earlier one.

## The fix: claim one row per order, always the earliest

```sql
SELECT *
FROM   outbox o
WHERE  o.status = 'PENDING'
  AND  o.id = (SELECT MIN(i.id) FROM outbox i
               WHERE i.aggregate_id = o.aggregate_id AND i.status = 'PENDING')
  AND  NOT EXISTS (SELECT 1 FROM outbox p
                   WHERE p.aggregate_id = o.aggregate_id
                     AND p.status = 'PARKED'
                     AND p.id < o.id)
ORDER BY o.id
LIMIT  :limit
FOR UPDATE SKIP LOCKED
```

The middle clause — `o.id = (SELECT MIN(i.id) ...)` — is the whole trick. It says: only consider a
row if it is the *earliest unsent* row for its own order. A second row for the same order simply
doesn't match this condition until the first one has left `PENDING`. No relay can see it, no matter
how many relays are running or in what order they happen to poll. **Ordering stops being something
the relay's own code has to arrange, and becomes a fact about what this query is even willing to
return.**

## Why `NOT EXISTS` is there — a parked row is a trap without it

Picture an order whose first row got parked after repeatedly failing, with a perfectly healthy
second row sitting behind it. `MIN(id)` alone doesn't know that — a parked row simply isn't
`PENDING` anymore, so `MIN()` steps right past it and hands out the *next* row instead, publishing a
later fact while the earlier one that caused all the trouble sits abandoned. That is precisely the
bug the whole parking decision exists to prevent, reappearing through a side door.

The `NOT EXISTS` clause closes it: "don't offer me any row belonging to an order that has an earlier
parked row." One order's saga is allowed to be stuck; every *other* order must keep moving, and this
is the line that keeps those two facts from getting confused with each other.

## What `FOR UPDATE SKIP LOCKED` actually locks here

Worth being precise about, since the query references the `outbox` table three times under three
different aliases (`o`, `i`, `p`). Only the rows the *outer* query returns — the `o` rows — are
locked by `FOR UPDATE`. The subqueries reference the same table but are not part of what gets locked;
that's ordinary PostgreSQL behaviour, not anything arranged specially here. `SKIP LOCKED` is what
makes a second relay, finding nothing left to claim, move on immediately rather than queue up behind
whichever relay got there first.

---

## The rejected alternative, and why

A `pg_try_advisory_xact_lock(hashtext(aggregate_id))` per order was considered — it would let more
than one row per order be claimed in a single poll, which this query deliberately does not allow.
Rejected anyway: advisory locks share one namespace across the *entire* PostgreSQL instance, and a
`hashtext` collision between two completely unrelated order ids would silently serialise them —
a bug nobody would notice happening, only notice *had* happened, much later, as an unexplained
slowdown. This query's cost by comparison is close to nothing: an order accumulates at most a
handful of outbox rows across its whole life, seconds apart, so limiting each poll to one row per
order barely delays anything real.

---

## Not yet exercised

This method compiles and is wired for `OutboxRelay` (T097) to call, but nothing has called it yet —
`OutboxRelay.pollAndPublish()`'s body is still the developer's to write (T099). Once it is, this
query is what most of Phase 4's guarantees — exclusivity, ordering, parked-row isolation — actually
rest on.
