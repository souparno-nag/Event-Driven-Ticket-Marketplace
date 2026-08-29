# T130 — `OutboxRecord`, ported

**What this task did:** copied order-service's `OutboxRecord` entity into this service's own `outbox`
package, unchanged in structure. This is the row this service's decision-making writes: not the seat
hold itself, but the durable statement "a message announcing this outcome must be sent."

---

## Why porting is the right call here, restated once more concretely

By this point in the build, the reasoning for copying rather than sharing this mechanism has been
recorded three times over — in `research.md` R8, in T122's migration, and again here. It's worth being
concrete about what "unchanged" actually means for this specific file, rather than just citing the
decision again.

Every field on `OutboxRecord` describes something true about *any* durable-outbox row, regardless of
which service owns it or which two message types it carries: a sequence number that defines publish
order, an aggregate id that's also the Kafka partition key, a channel name, a serialized payload,
optional trace context, a status, an attempt counter, an error message, and two timestamps. None of
that is order-service-specific. The only things that actually differ between the two services' copies
are in the *comments*, not the code: this file's Javadoc now says "seats.reserved or seats.rejected"
where order-service's says "order.created", and it points at `V4__create_outbox.sql` instead of
`V2__create_outbox.sql`. The mechanism itself needed zero redesign.

---

## The one comparison worth re-explaining locally

`OutboxRecord`'s own Javadoc contrasts its database-generated `id` with the application-assigned id on
`Reservation` (T126) — the same contrast order-service draws between `OutboxRecord` and its own
`Order`. Restating it here because it's the kind of thing worth understanding once and recognizing
everywhere: `Reservation`'s id is decided in Java because another row in the *same transaction* — this
very outbox row — needs to reference it before anything is committed. `OutboxRecord`'s own id has no
such constraint; nothing else needs to know it in advance, and what actually matters about it is that
it's *monotonic*, which only a database sequence reliably provides. Two entities in the same service,
solving the identical-looking "what generates the id" question with opposite answers, because they're
actually different questions once you ask *why* each id needs to exist.

---

## Verifying it

Verified as part of a combined smoke test with T131–T133 (see those write-ups for the shared
methodology): a real `OutboxRecord` was persisted against a live PostgreSQL 16 database with the actual
`outbox` table from T122, and correctly round-tripped through `markPublished()`, confirming the
`jsonb` column mapping (`@JdbcTypeCode(SqlTypes.JSON)`) and the entity's field mappings both work
against the real schema rather than merely compiling.
