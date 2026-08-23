# T085 — `statement_timeout`, enforced by the database itself

**What this task did:** added one line to `application.yml`, giving PostgreSQL its own reason to cut
off a slow query, independent of anything the application decides to do about it.

```yaml
connection-init-sql: SET statement_timeout = 3000
```

---

## Why the application's own timeouts are not enough

Two timeouts already exist in this service: Hikari's `connection-timeout: 250` (how long a request
waits for a free connection) and `spring.transaction.default-timeout: 3s` (how long a whole
transaction may run). Both are enforced by **this application**, on **this side** of the network
connection to PostgreSQL.

That leaves a gap. Picture a query that hangs *inside* PostgreSQL itself — waiting on a lock another
transaction is holding, say, or a query plan that goes badly wrong. The application's transaction
timeout is a clock the application keeps; it can eventually give up and throw an exception locally,
but the query it was waiting on is still running on the database server, on the same connection,
regardless of what the client-side code decides to do about it. A client-side timeout cannot reach
into the server and stop a query the server itself hasn't been told to stop.

## What `connection-init-sql` actually does

`SET statement_timeout = 3000` is a PostgreSQL session setting: any single statement that runs longer
than 3000 milliseconds is cancelled by the **database itself**, from inside, regardless of what the
client is doing. Hikari runs this SQL once, automatically, every time it opens a **new physical
connection** to add to the pool — not on every borrow, just once per connection's lifetime — which is
exactly the right moment: it sets the session's timeout as soon as the session exists, before any
query using that connection ever runs.

The value matches the application's own 3-second transaction limit deliberately. There is no reason
for the two numbers to disagree — a lower database timeout would cut a legitimate query off before
the application's own patience had run out; a higher one would let a stuck query outlive the
transaction that was supposedly bounding it.

---

## Where this fits with what was already there

| Guard | Enforced by | Stops |
|---|---|---|
| `connection-timeout: 250` | Hikari, client-side | Waiting too long *for* a connection |
| `spring.transaction.default-timeout: 3s` | Spring, client-side | A transaction running too long, as the client sees it |
| `statement_timeout = 3000` | PostgreSQL, server-side | A query the server itself is stuck on, regardless of what the client believes |

All three together are what make FR-035's "a slow store degrades into fast refusals" genuinely true,
rather than true only for the specific ways a request can be slow that the application happens to be
watching for.
