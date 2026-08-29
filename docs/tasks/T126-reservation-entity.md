# T126 — The `Reservation` entity

**What this task did:** wrote `Reservation.java`, the JPA mapping for the table T120 created. This is
the durable record this whole service exists to protect — the thing PostgreSQL knows that Redis is
only ever a fast, forgetful cache of.

---

## Assigning the id in Java, not the database — and why that isn't just a style choice

```java
@Id
@Column(name = "reservation_id")
private UUID reservationId;
```

No `@GeneratedValue`. The application decides this value, in the constructor, before the row exists.
That mirrors exactly how `order-service`'s `Order` assigns its own id — and the reason is the same
one, restated for this service's own shape of the same problem.

`ReservationService`, arriving in a later task, has to write four things in one transaction: the
reservation row, its seat rows, the processed-message row, and an outbox row announcing the outcome.
That outbox row needs to carry the reservation's id — it's the value announced as `reservationId` on
`SeatsReserved`, and step 4 will need it to name which reservation to commit. If the database assigned
the id, getting it back would mean flushing the insert *before* the transaction finishes, purely to
learn a value that's about to be embedded in another row in the very same unit of work. Deciding it in
Java sidesteps that entirely — the id exists the moment the constructor runs, and every other row in
the transaction can reference it immediately.

---

## Two relationships that were deliberately never built

```java
@Column(name = "show_id", nullable = false)
private UUID showId;
```

Just a `UUID`. Not a `@ManyToOne` to `Show`. This is worth pausing on, because it would be easy to
assume a "proper" object model should link a reservation to the show it belongs to. Two things argue
against it here. First, nothing in this service's code ever actually navigates from a reservation to
its show as an object — every read of `showId` is a raw value, used directly to build a Redis key or
fill in an outbox payload field, never a path to load more `Show` data. Second, and more subtly: a
`@ManyToOne` invites Hibernate's lazy-loading machinery, which is exactly the kind of thing that works
fine in development and then throws `LazyInitializationException` the first time someone tries to read
the association after its transaction has already closed — a failure mode `order-service`'s own `Order`
class explicitly calls out avoiding.

There's a second relationship that's absent for an entirely different reason: **no foreign key, and no
JPA relationship, into `order-service`'s own `orders` table.** This isn't a modeling oversight — the
two services are only supposed to agree about an order's existence by *exchanging messages*. A foreign
key here would make this service's writes depend on `order-service`'s schema being reachable and
consistent, which is precisely the coupling a choreographed saga is built to avoid. If order-service's
table were ever unreachable, this service's ability to hold a seat should not go down with it.

---

## The one status transition this step actually needs, named explicitly

```java
public void expire() {
    this.status = ReservationStatus.EXPIRED;
}
```

`order-service`'s `Order` has a generic `changeStatus(OrderStatus next)` method instead of specific
ones — and that was the right call *there*, because none of `Order`'s future transitions had a real
caller yet to design a guard against. `Reservation` is in a different position: it has exactly one
reachable transition this build step, with a real caller and a real rule already known —
`ReservationService`'s inline retirement of a lapsed reservation, contending for the same seats a new
booking wants (FR-018). Naming the method `expire()` documents that specific, already-understood rule
right where it happens, rather than leaving "which transitions are even legal from here" implicit in
whatever value a caller happens to pass to something generic. When `COMMITTED` and `RELEASED` get real
callers in steps 4 and 5, each gets its own equally specific method then — not a preemptive one now,
guessing at rules nobody has written yet.

---

## Verifying it

Verified as part of the same round-trip check described in T125's write-up: a `Reservation` was
constructed with a real id, order id, show id (the seeded "Load Test Hall"'s), and a lock-expiry 120
seconds out; persisted via `EntityManager.persist()`; and reloaded through
`ReservationRepository.findByOrderId()` against a real PostgreSQL 16 database with the actual
migrations applied. The reload confirmed the status came back as `HELD` and the `@Version` column was
populated by Hibernate — both properties this class claims but cannot prove by itself, since a
`@Version` field does nothing until Hibernate actually manages an update against a real table.
