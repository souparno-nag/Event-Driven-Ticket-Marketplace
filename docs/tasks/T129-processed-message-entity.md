# T129 — The `ProcessedMessage` entity

**What this task did:** wrote the Java side of T121's migration — `ProcessedMessage`,
`ProcessedMessageId`, and `ProcessedMessageRepository`. This is the entity behind this service's
idempotency guard: the durable note that a given message has already been handled by a given consumer.

It's a small, quiet task compared to the reservation entities, and that's appropriate — this table's
entire job is to be simple and hard to get subtly wrong, because the mechanism built on top of it
(`IdempotencyGuard`, arriving stubbed in a later task) is the one piece of the delivery path a human
writes by hand.

---

## The same shape as `ShowSeatId` and `ReservationSeatId`, for the same reason

`processed_messages`' primary key, from T121, is the composite pair `(message_id, consumer_name)` —
the whole reason that key is composite rather than a single column is explained fully in T121's own
write-up (the brief's original single-column key would let the first consumer in this database to
handle a message silently lock every other consumer out of it). Modeling that composite pair means a
third `@EmbeddedId` class in this service, following the exact pattern `ShowSeatId` (T125) and
`ReservationSeatId` (T127) already established:

```java
@Embeddable
public class ProcessedMessageId implements Serializable {
    private UUID messageId;
    private String consumerName;
    // hand-written equals/hashCode — both fields fixed at construction, never regenerated
}
```

Three composite-key entities in this service now, all built identically. That consistency is worth
naming explicitly: a reader who's worked through why `ShowSeatId` looks the way it does doesn't have to
re-derive the reasoning twice more to understand `ReservationSeatId` and this one — the shape of the
problem (a natural composite key, decided once, never regenerated) and the shape of the solution
(`@EmbeddedId` plus hand-written value equality) are the same each time.

`ProcessedMessage` itself carries only one more field beyond its id — `processedAt` — set by a
`@PrePersist` callback for the same reason `order-service`'s `OutboxRecord.onInsert()` does: the
column is `NOT NULL DEFAULT now()`, but Hibernate's generated `INSERT` names every mapped column
explicitly, including this one as `NULL` if the Java field was never set — and an explicit `NULL`
overrides the database's own `DEFAULT` rather than falling through to it.

---

## An empty repository, and why that's the correct amount of code

```java
public interface ProcessedMessageRepository extends JpaRepository<ProcessedMessage, ProcessedMessageId> {
}
```

Nothing inside it. This matches `order-service`'s own `OrderRepository`, which is empty for the
identical reason: the guard's entire job, per `contracts/inventory-consumer.md`, is to attempt one
`INSERT` inside the caller's transaction and treat a constraint violation on it as "already handled."
`existsById` is available from `JpaRepository` if some future caller ever needs a check-without-insert,
but the guard itself is never supposed to check first and insert second — doing that would open a
window between the check and the insert where two concurrent redeliveries of the same message could
both see "not yet processed" and both proceed, which is exactly the race this table exists to close.
Attempting the insert directly and catching the failure is what makes the check and the claim happen as
one atomic database operation rather than two separate steps with a race hiding in between.

That's also why this repository doesn't yet contain the guard logic itself. `IdempotencyGuard` — the
class that actually performs the insert-and-catch, deliberately left as a stub with its contract
written out for the developer to implement by hand (CLAUDE.md requirement 3) — is a later task. This
entity and its repository only have to exist correctly for that guard to have something to call.

---

## Verifying it

Verified against a real PostgreSQL 16 database, alongside T125–T128's checks: a `ProcessedMessage` was
saved for a fabricated message id under the consumer name `"inventory-service"`, flushed, and then
confirmed present via `existsById` against the same composite key — proving the entity maps correctly
onto the real table and that the composite key round-trips through Hibernate's equality checks
correctly (a bug in `ProcessedMessageId`'s hand-written `equals`/`hashCode` would make `existsById`
either always miss or throw, not silently succeed).

This closes out Phase 2's domain layer. What's built so far exists and is provably correct against a
real schema, but nothing yet *decides* anything — no consumer, no Redis interaction, no outbox writer.
Those arrive in the tasks immediately following this one.
