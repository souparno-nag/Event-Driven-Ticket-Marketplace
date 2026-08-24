# T096 — `OutboxMetrics`, and why the two gauges never trust a cache

**What this task did:** wrote the five meters `research.md` R12 specifies — two counters the relay
will call directly, and two gauges that need no calls from anything, because they read the live
database on every single scrape.

---

## Age, not depth — the choice this task is really about

```java
Gauge.builder("outbox.oldest.pending.age.seconds", this, OutboxMetrics::readOldestPendingAgeSeconds)
```

The obvious meter to reach for is a **count** of pending rows. It's the wrong one, and the reason
why is worth sitting with: a burst of a hundred new orders arriving at once makes the pending count
spike immediately and harmlessly — every one of those rows might get sent within the next second,
exactly as designed. A count spiking is completely indistinguishable, from the outside, between "a
burst just arrived" and "the relay has actually stopped working."

**Age** doesn't have that ambiguity. The oldest unsent row's age can only keep growing if rows are
arriving faster than the oldest one is being cleared — which is precisely, and only, what "the relay
is losing ground" means. A hundred rows that all arrived a second ago and a hundred rows that have
each waited an hour look identical to a row-count meter and completely different to an age meter.

## Reading the database every time, on purpose

```java
private double readOldestPendingAgeSeconds() {
    Double seconds = jdbc.queryForObject(
            "SELECT EXTRACT(EPOCH FROM (now() - min(created_at))) FROM outbox WHERE status = 'PENDING'",
            Double.class);
    return seconds == null ? 0 : seconds;
}
```

The tempting shortcut is to have the relay update an in-memory value each time it runs, and have the
gauge just report that cached number. That shortcut has a specific, dangerous failure mode: if the
relay stops running entirely — crashes, deadlocks, gets stuck — the *cached* value keeps reporting
whatever it last saw, which can go on looking healthy for a long time after the thing it's supposed
to be measuring has actually died. Querying the database directly on every scrape means the gauge can
never be more stale than however often Prometheus asks — it reports what's *currently* true, not what
was true the last time the relay happened to notice.

This is affordable specifically because of a decision made two batches earlier: `idx_outbox_pending`
and `idx_outbox_parked` (V2, T063) are partial indexes covering exactly the rows these two queries
need, so both scale with the backlog rather than with the whole table's history.

## A `NULL` that means something ordinary, not something wrong

`MIN(created_at)` over a `WHERE` clause matching zero rows returns SQL `NULL` — the healthy, common
state of "nothing is currently backlogged," not a database problem worth raising. The `seconds == null
? 0 : seconds` guard is what turns that ordinary case into the honest answer, zero, rather than
letting a `NullPointerException` surface from what is actually good news.

## A small mistake caught and removed before it shipped

The first draft of this class added a private nested interface —

```java
private interface DoubleSupplier extends ToDoubleFunction<OutboxMetrics> {
}
```

— on the theory that `Gauge.builder`'s method-reference argument needed a named shape to bind to. It
didn't: `OutboxMetrics::readParkedCount` already satisfies `ToDoubleFunction<OutboxMetrics>` on its
own, since a private instance method returning `double` with no other arguments is exactly that
shape. The interface compiled, was never referenced by anything, and existed purely because it
*looked* like the kind of scaffolding this pattern usually needs. Removed once actually questioned —
a small example of the same discipline the project's constitution asks for: no abstraction without a
demonstrated need, and "it looked necessary" is not a demonstration.

---

## Not yet exercised

`recordPublished()` and `recordSendFailure()` are ready for the relay to call, but nothing calls them
yet — that's T099's job. The two gauges, though, work today, independent of the relay entirely, since
they only ever ask the database a direct question.
