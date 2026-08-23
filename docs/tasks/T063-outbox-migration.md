# T063 — The `outbox` table

**What this task did:** created the table that makes "record the order" and "tell everyone about it"
into a single atomic act.

---

## The problem, stated plainly

Accepting a booking means two things must both happen:

1. Store the order in PostgreSQL.
2. Publish a message to Kafka so the seat-locking service picks it up.

Two different systems. There is no way to commit both together. So a crash between them gives you
one of two bad outcomes:

- **Stored, not published** → the buyer was told "accepted" and nothing ever happens. Their seats are
  never held. The order sits pending forever.
- **Published, not stored** → seats get locked for an order that does not exist.

## The trick

Do not talk to Kafka at all while accepting the order. Instead write **two rows to the same
database** in one ordinary transaction: the order, and a row in this table saying "a message needs
sending". One database, one transaction — atomic for free.

A background job reads unsent rows afterwards and publishes them.

What remains is a much weaker problem. If the job crashes after publishing but before ticking the
row off, the message gets sent **twice**. Duplicates are survivable: every message carries a unique
id, and consumers remember which ids they have already handled. Losing a message is not survivable.
The outbox trades an impossible guarantee for an easy one.

---

## The columns that are not obvious

### `payload JSONB` — serialized once, at write time

The message is converted to JSON when this row is written, not when it is sent. That matters: it
means what a consumer eventually receives was decided at the moment the order was accepted. If the
message were built at send time instead, deploying new code in between would silently change a
message that had already been promised.

**A trade-off worth knowing.** `jsonb` is PostgreSQL's parsed JSON type. It normalises what you
store — object keys get reordered, whitespace dropped — so the bytes you read back are not
byte-identical to the bytes you wrote. Plain `TEXT` would preserve them exactly.

`jsonb` was chosen anyway, because consumers parse the JSON rather than comparing bytes, and being
able to run `SELECT payload->>'amount' FROM outbox` while diagnosing a stalled saga is worth far
more than byte-preservation nobody needs.

### `traceparent` and `tracestate` — keeping one trace across the gap

Distributed tracing lets you click one request and see everything it caused, across every service.
The outbox deliberately breaks the chain: the request commits and returns, and the message is sent
later on a different thread. Without help, the tracing UI shows two unrelated fragments.

These two columns carry the trace identity — `traceparent` is a W3C standard format — so the relay
can say "this publish belongs to that request" minutes after the fact.

They are **columns, not fields inside `payload`**, and that is deliberate. The message shapes were
frozen in build step 1 and are shared by every service. Putting tracing data inside one would mean
changing how the system is observed forces a change to a shared contract. Keeping it outside means
the two can never entangle.

### `status`, `attempts`, `last_error` — knowing when to give up

The original sketch of this table used `published_at IS NULL` to mean "not yet sent". That
distinguishes two states, and three are needed.

Consider a row that can never be sent — a malformed payload, a channel that no longer exists.
Retried forever, it burns work silently and, because messages for one order must be sent in order,
it blocks every later message for that buyer. Their booking simply stops, with nothing raising a
hand.

So each row counts its failed attempts and records why the last one failed. After a limit it is
**parked**: retries stop, the row is kept for inspection, and a metric reports it. Failing loudly
beats spinning quietly.

`status` is now the authoritative field and `published_at` records *when* publication happened. A
`CHECK` constraint ties them together:

```sql
CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL))
```

Two pieces of state that describe the same fact will drift apart eventually. This makes drift
impossible rather than unlikely.

### No foreign key on `aggregate_id`

It holds an order id, but there is no `REFERENCES orders(id)`. The outbox is a **log of things that
must be said**, not a child of the order. A foreign key would force a decision about what happens to
already-sent messages when an order is archived, and would block a future step from recording a
message about something that is not an order.

---

## Partial indexes — the neat part

```sql
CREATE INDEX idx_outbox_pending
    ON outbox (aggregate_id, id)
    WHERE status = 'PENDING';
```

That `WHERE` clause makes it a **partial index**: only rows matching it are indexed at all.

The relay asks the same question every half second — "what is unsent?" — and this index answers it.
Because published rows *leave* the index, its size tracks the **backlog**, not the table. A table
holding ten million successfully sent messages polls exactly as fast as an empty one. A normal index
would grow forever and get slower forever.

The second partial index covers parked rows, and is tiny by construction: a healthy system has none.

---

## Not yet run

Written and committed, but not yet executed against a database. T072 proves it applies and that the
Java entities agree with it.
