# T132 — `OutboxMetrics`, ported and retagged

**What this task did:** copied order-service's `OutboxMetrics` — two counters and two gauges
describing the relay's own health — into this service, with one deliberate change: every meter name
now carries an `inventory.` prefix instead of order-service's bare `outbox.*` names.

---

## Why "retagged" specifically, and what that decision actually was

Task `T132`'s own description says "ported, retagged for this service" without spelling out exactly
what retagging means, so it's worth being explicit about the judgment call made here rather than
leaving it implicit in a diff.

Two services now each run their own outbox relay, and each will report metrics like "how many rows
have I published" and "what's my oldest unsent row's age" to the same Prometheus instance. Prometheus
itself has no trouble telling them apart — every scrape carries `job` and `instance` labels that
identify which process a given data point came from, regardless of what the metric itself is named. So
this isn't a correctness problem; nothing would actually break by porting the four meter names
unchanged.

The reason to rename anyway is about the *next* person looking at a dashboard or writing an ad hoc
query. A panel titled "outbox records published" that's actually only ever wired to one service's job
label looks correct right up until someone duplicates that panel for the second service and forgets to
also duplicate the label filter — at which point the graph silently shows a sum, or the wrong service's
data, with nothing to flag the mistake. Naming the metric `inventory.outbox.records.published` instead
of `outbox.records.published` makes that mistake require actively ignoring the metric's own name, not
just missing a label filter three panels deep.

`inventory.outbox.oldest.pending.age` gets a second, more specific reason: `research.md`'s R13 already
names this *exact* meter, under this *exact* name, as one of the five meters this whole service commits
to exposing (`DecisionMetrics`, a later task, adds the other four — holds granted, holds refused,
decision duration, and messages dead-lettered). Naming it to match R13 now means `DecisionMetrics`
doesn't have to either duplicate this gauge or invent a second name for the same underlying number
later.

One more small, deliberate change alongside the rename: order-service's gauge is literally named
`outbox.oldest.pending.age.seconds`, with the unit spelled out in the name. This port drops the
`.seconds` suffix to match R13's name exactly, and moves that unit information into the gauge's
`.description(...)` instead — a Prometheus convention that's arguably better practice regardless
(`_seconds` as a name suffix is a Prometheus-native-metrics idiom; Micrometer's own convention leans
toward describing units in the meter's description rather than baking them into the name), but the
actual reason it's done here is simply to keep the name exactly what R13 already committed to.

---

## Everything else is unchanged, because none of it needed to be

The gauges-measure-age-not-depth reasoning, and the gauges-query-the-database-live-not-a-cached-value
reasoning, both carry over word for word from order-service's own class. Neither argument has anything
to do with which two message types this outbox carries — a stalled relay looks the same regardless of
whether the messages behind it are order confirmations or seat holds.

---

## Verifying it

Constructed directly — no Spring context needed for this class specifically, since its constructor
takes only a `JdbcTemplate` and a `MeterRegistry`, both plain objects — against a real PostgreSQL 16
database with the `outbox` table from T122 already in place. Both gauges were forced to evaluate (a
Micrometer `Gauge`'s supplier runs lazily; merely registering one proves nothing until something reads
`.value()`), and both correctly returned `0.0` against an empty table — confirming the
`MIN() OVER ZERO ROWS IS NULL` case both methods document is handled, rather than throwing, on a real
database rather than merely being asserted in a comment.
