# T127 — The `ReservationSeat` entity

**What this task did:** wrote `ReservationSeat.java` — the entity that maps one claimed seat within
one reservation. Along the way, it added a second file the task's own description didn't name:
`ReservationSeatId.java`. Both decisions are explained below, because the second one is a genuine
judgment call worth being explicit about rather than quietly slipping in.

---

## Why this couldn't be an `@ElementCollection`, unlike order-service's seats

`order-service`'s `Order` holds its seats as a simple `@ElementCollection<String>` — a set of plain
seat labels, backed by a child table, with nothing else attached to each one. That works there because
an order's seats never change once written: they're recorded at acceptance and never touched again in
this build step.

`reservation_seats` needs something an `@ElementCollection` of plain strings can't carry: a per-seat
mutable fact. Specifically, `released_at` — the column that goes from `NULL` to a real timestamp the
moment a specific seat, not the whole reservation, stops being claimed. Retiring a lapsed reservation
touches exactly the seats being contended for, potentially a subset of what the reservation originally
held once step 5's release path exists. A collection of interchangeable strings has no way to update
one particular element's state; you'd have to remove and re-add it, which loses the composite primary
key's own guarantee about uniqueness along the way. A proper entity, with its own identity and its own
mutable column, is what a per-row mutable fact actually needs.

---

## The file the task didn't name: `ReservationSeatId`

T127's description in `tasks.md` says only: *"Create `ReservationSeat.java`... carrying `show_id` and
`released_at`."* One file. But `reservation_seats`' primary key, from T120, is the composite pair
`(reservation_id, seat_label)` — there's no surrogate `id` column to fall back on. JPA's clean way to
model a composite key that's genuinely a *value* (not database-generated, never recomputed) is
`@EmbeddedId` backed by a small `@Embeddable` class, and that class needs its own file.

This is worth being upfront about rather than treating as implied: **`ReservationSeatId.java` was
added beyond what T127's task text literally listed.** The reasoning for adding it rather than finding
some other way to avoid it: this service already has exactly this shape of problem solved once, in
T125 — `ShowSeatId`, an `@Embeddable` sitting alongside `ShowSeat`. Building `ReservationSeat`'s
composite key the same way keeps both composite-key entities in this service built identically, rather
than one using `@EmbeddedId` and the other reaching for a different mechanism (`@IdClass`, say) to
solve the structurally identical problem a few files away. Consistency here isn't cosmetic — a reader
who's already understood `ShowSeatId` doesn't have to learn a second pattern to understand this one.

```java
@Embeddable
public class ReservationSeatId implements Serializable {
    private UUID reservationId;
    private String seatLabel;
    // hand-written equals/hashCode, same reasoning as ShowSeatId
}
```

---

## Why `ReservationSeat` still carries no relationship to `Reservation`

Consistent with every other choice in this service so far: `reservationId` lives as a plain field
*inside* `ReservationSeatId`, not as a `@ManyToOne` association. Nothing in this service ever walks
from a seat row to its parent reservation as an object graph. The one place that genuinely needs both
together — the lapsed-seat lookup `ReservationRepository` needs (T128) — joins them by raw column
equality in a native SQL query, exactly the way `order-service`'s `OutboxRepository` avoids relying on
JPA relationships for its own cross-row query.

---

## `released_at` versus the parent's `status` — restated at the row that actually matters

This gets its fullest explanation in `V2__create_reservations.sql` and `ReservationStatus`'s own
Javadoc, but it's worth restating here because this is the field where it's actually enforced. A
reservation's `status` and a seat's `releasedAt` look at first like they should just mirror each
other — but they answer different-grained questions. `status` describes the *reservation* as a whole:
is it held, expired, committed, released? `releasedAt` describes *one seat within it*: is this
particular claim live right now? Those coincide today, because a reservation currently claims all its
seats or none of them — but the column exists on the seat, not derived from the parent, specifically
so that a future step (a partial release, say) has somewhere to record a per-seat fact without needing
a redesign. Right now, exactly one method sets it — `release(Instant when)` — called from exactly one
place once `ReservationService` exists.

---

## Verifying it

Verified as part of the same round-trip check described in T125 and T126's write-ups: a
`ReservationSeat` was constructed for seat `A1` against a freshly persisted `Reservation`, persisted
via `EntityManager.persist()`, and then queried back through `ReservationRepository`'s lapsed-seat
lookup — twice, with two different `asOf` instants. Querying with `asOf` set to "now" (before the
lock's 120-second lifetime had elapsed) correctly found nothing; querying with `asOf` set to a moment
*after* the lock's expiry correctly found the reservation, proving the join, the composite key, and the
`released_at IS NULL` condition all cooperate correctly against the real schema.
