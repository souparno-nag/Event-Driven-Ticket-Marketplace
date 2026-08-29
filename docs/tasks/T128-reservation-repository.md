# T128 — `ReservationRepository`

**What this task did:** wrote the repository for `Reservation`, with three lookup methods, each
serving a different real need rather than existing "just in case": one for finding a reservation by
its order, one for the startup rebuild, and one for inline retirement of lapsed reservations. It's
also where a real configuration bug — left over from T117 — actually surfaced and got fixed.

---

## Three methods, three different jobs

**`findByOrderId(UUID orderId)`** is the plain one: a Spring Data derived query, generated entirely
from its name, no `@Query` needed. It's the read side of the `order_id UNIQUE` constraint
`V2__create_reservations.sql` declares — the shape something reaches for when it needs to answer "what
happened to this order's seats" without already knowing the reservation's own id.

**`findByStatusAndLockExpiresAtAfter(HELD, asOf)`** answers exactly the question the startup rebuild
needs answered: which reservations are currently held and haven't lapsed yet, as of some instant? This
is what a later task's `SeatLockRebuilder` will call to know which holds to replay into Redis before
this service starts consuming booking requests at all (FR-015) — get that ordering wrong, and the
first request after a restart is judged against a Redis that has forgotten every existing hold.
`asOf` is a parameter rather than something this method computes internally from `Instant.now()`,
specifically so a test can rebuild against a fixed instant instead of racing the real clock — the same
reasoning that keeps timestamps as parameters rather than hidden calls throughout this codebase.

**`findLapsedReservationsCoveringSeats(showId, seatLabels, asOf)`** is the interesting one, and the one
that needed the most care.

---

## Why the third method has to be native SQL, not JPQL

This query answers: "of the seats a new booking wants, which ones are covered by a reservation that's
`HELD` in name only — its lock already expired, but nothing has retired it yet?" `ReservationService`
will call this and retire whatever comes back, in the *same transaction* as its own new booking
(FR-018) — otherwise Redis frees a seat the instant its TTL lapses while PostgreSQL still calls the old
reservation `HELD`, and the very next legitimate booking gets rejected by `ux_reservation_seat_live`
for a reservation that's actually dead.

Answering that question means joining `reservations` and `reservation_seats` — two entities that, by
deliberate choice in T126 and T127, carry no JPA relationship to each other. JPQL's clean join syntax
generally wants a mapped association to walk; without one, the natural tool is a native SQL query that
joins by raw column equality, exactly the same choice `order-service`'s own `OutboxRepository` makes
for its own cross-row logic:

```sql
SELECT DISTINCT r.*
FROM   reservations r
JOIN   reservation_seats rs ON rs.reservation_id = r.reservation_id
WHERE  rs.show_id = :showId
  AND  rs.seat_label IN (:seatLabels)
  AND  rs.released_at IS NULL
  AND  r.lock_expires_at <= :asOf
```

`SELECT DISTINCT r.*` rather than `rs.*` matters: the caller retires whole *reservations*, and a single
lapsed reservation covering three of the requested seats must come back once here, not three times.

---

## The bug this method's own testing exposed

Writing this specific query is what finally forced a real end-to-end boot of the module against a live
database — and that's what caught a configuration mistake sitting quietly in T117 since it was
written.

The short version: T117's `application.yml` set `spring.jpa.hibernate.default-schema: inventory` —
which does nothing. Spring Boot's `HibernateProperties` class only recognizes two fields under that
prefix, `ddlAuto` and `naming`; anything else placed there is silently dropped, no warning printed
anywhere. Hibernate was validating every entity against PostgreSQL's default `public` schema the whole
time, where none of this service's tables actually live — it just had nothing to validate *against*
until real entities existed, which is exactly what this batch of tasks provided.

Fixing that (moving the setting to `spring.jpa.properties.hibernate.default_schema`, the property that
actually reaches Hibernate) got the application context loading again — but this specific native query
*still* failed, with PostgreSQL reporting `relation "reservations" does not exist`. The reason: fixing
Hibernate's own schema setting only affects the SQL Hibernate *generates* from JPQL. A native query is
handed to the JDBC driver exactly as typed, and PostgreSQL resolves an unqualified table name against
whatever `search_path` the *connection* carries — which nothing had touched, so it stayed at Postgres's
own default of `public`. The actual fix was adding `?currentSchema=inventory` to the JDBC URL itself, a
PgJDBC parameter that sets `search_path` once per connection, covering both native and
Hibernate-generated SQL. Both corrections are recorded in full, with the exact failing and passing
output, as their own commit against T117.

The reason this is worth retelling here rather than only in that commit: it's a direct example of why
this native query — and this whole batch of entities — got tested against a *real* PostgreSQL instance
rather than accepted on the strength of reading the file. A config typo that Spring Boot swallows
silently produces code that looks completely correct and fails only the moment something actually asks
the database a question.

---

## Verifying it

All three methods were exercised together, against a real PostgreSQL 16 database with the actual
Flyway migrations applied, as described in T125–T127's write-ups:

- `findByOrderId` correctly reloaded a persisted reservation by its order id.
- `findByStatusAndLockExpiresAtAfter(HELD, now)` correctly included a freshly created, unlapsed
  reservation.
- `findLapsedReservationsCoveringSeats` correctly returned nothing when queried with `asOf` before the
  reservation's lock expiry, and correctly returned that exact reservation when queried with `asOf`
  after it — the precise behavior FR-018's inline retirement depends on.
