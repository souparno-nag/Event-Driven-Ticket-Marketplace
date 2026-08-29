# T131 — `OutboxRepository`, ported

**What this task did:** copied order-service's `OutboxRepository` — specifically its `claimBatch`
native query — into this service, unchanged in its SQL. This single query is what lets multiple relay
instances (or, in this build step, a single one) safely claim work without ever double-sending a
message or sending two messages for one order out of sequence.

---

## Why "unchanged in the SQL" is the actual point of porting this file

`claimBatch`'s guarantees are worth restating precisely because they're easy to under-credit: this one
query is doing the work of "one PENDING row per order at a time, oldest first, never a row for an order
that already has an earlier row parked, and never a row someone else already has locked." All four of
those properties come from the query's own predicate structure — the `MIN(id)` subquery, the
`NOT EXISTS` guard against parked rows, and `FOR UPDATE SKIP LOCKED` — not from any coordination
written into whatever calls it. That's precisely why this file needed zero changes to fit a second
service: the guarantees are properties of the `outbox` table's *shape*, which T122's migration made
identical to order-service's, not properties of what the rows inside it mean.

The only thing worth double-checking when porting a *native* query specifically — as opposed to a JPQL
one — is whether it depends on anything about where it runs. It doesn't reference a schema name
directly (the table is written as bare `outbox`), which means it relies on the *connection's*
`search_path` to resolve correctly — precisely the mechanism T117's correction commit put in place
after this same class of query first exposed the missing piece. This query is a second, independent
confirmation that fix actually generalizes: it's a different native query, in a different repository,
touching a different table, and it worked immediately once the JDBC URL's `currentSchema=inventory`
was in place.

---

## Verifying it

Verified as part of the combined smoke test described in T130 and T132's write-ups. A real
`OutboxRecord` was persisted, and `claimBatch(10)` correctly returned it — the native query executing
successfully against `inventory.outbox` through the same connection pool every other repository in this
service uses. After the relay marked that row `PUBLISHED`, a second call to `claimBatch(10)` correctly
excluded it, confirming the `WHERE o.status = 'PENDING'` clause does its job against a real, changed
row rather than only against the migration's freshly created, empty table.
