# T062 — The `orders` table

**What this task did:** wrote the first database migration, creating `orders` and `order_seats`.

---

## What a migration is

A **migration** is a numbered SQL file describing one change to the database. Flyway runs them in
order and records which it has run in a table it keeps for itself. Run the application against an
empty database and it applies `V1`, then `V2`. Run it against a database that already has `V1` and
it applies only `V2`.

The important property: migrations are **append-only**. You never edit a file that has already run
anywhere — Flyway stores a checksum and refuses to start if one changes underneath it. To alter the
schema you add `V3`. This is what keeps a fresh checkout and a long-running database converging on
the same structure instead of quietly diverging.

---

## Decisions in this file

### The application generates the id, not the database

```sql
id UUID PRIMARY KEY          -- no DEFAULT, no sequence
```

Most tables let the database assign identifiers. Here the application creates the UUID before
inserting. The reason is the outbox: the order row and its outbox row are written in one
transaction, and the outbox row needs to record which order it concerns. If the database assigned
the id, the code would have to insert the order, ask the database what it decided, and only then
write the outbox row. Generating it up front removes that round trip entirely — and the same value
becomes the saga correlation id and the Kafka partition key.

### `NUMERIC(19,2)`, never `float` or `double`

Binary floating point cannot represent `0.10` exactly, in the same way decimal cannot represent
one third. Add ten of them and you get `0.9999999999999999`. For money that is a bug.

`NUMERIC` stores digits, so `150.00` is exactly one hundred and fifty. This project needs that twice
over: the simulated payment in step 4 decides success from the last digit of the amount, and the
load test in step 9 asserts exact totals.

### `show_id`, never `event_id`

In this system the word **event** already means "a message". A column called `event_id` that
actually meant "which concert" would collide with the identity of the messages themselves. Build
step 1 renamed it, and the name is kept consistent everywhere since.

### Seats get their own table

```sql
CREATE TABLE order_seats (
    order_id  UUID         NOT NULL REFERENCES orders (id) ON DELETE CASCADE,
    seat_id   VARCHAR(32)  NOT NULL,
    PRIMARY KEY (order_id, seat_id)
);
```

An order has several seats, and there were three ways to store them: a text array column, a JSON
blob, or a separate table.

The separate table wins because of that **composite primary key**. It makes "seat A1 twice on the
same order" impossible *in the database*. The application also rejects duplicates, but application
validation only holds while every code path remembers to call it. A primary key holds regardless.

The cost is a join to read one order's seats — accepted, because an order has a handful of seats and
is never read in bulk. `ON DELETE CASCADE` means removing an order removes its seats, rather than
leaving rows pointing at nothing.

### The `version` column, for a feature nothing uses yet

```sql
version BIGINT NOT NULL DEFAULT 0
```

This is **optimistic locking**. When two users update the same row at once, the naive outcome is
that the second overwrites the first and nobody notices. With a version column, updates carry
`WHERE id = ? AND version = ?`. The winner's update matches and bumps the version; the loser's
matches nothing, updates zero rows, and gets an error saying so.

Nothing updates an order in this build step — the only transition is *into* `PENDING` at creation.
The column is here anyway because adding it later means both a migration and backfilling a version
number onto every row that already exists.

### `CHECK` constraints

```sql
CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED'))
CHECK (amount >= 0)
```

A `CHECK` is a rule the database enforces on every write, whatever wrote it. All three statuses are
listed even though only `PENDING` is reachable today — otherwise the first confirmed order in step 4
would be rejected at exactly the moment the saga finally works end to end.

---

## Not yet run

This file has been written and committed but **not executed against a real database**. Proving that
Flyway applies it and that the Java entities match it is T072, which needs the test setup from
T071 first.
