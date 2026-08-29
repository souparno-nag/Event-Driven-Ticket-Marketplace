# T154 — `SeatLockStore`

**What this task did:** wrote `SeatLockStore`, the one class in this service that actually talks to
Redis for contention — builds the keys, evaluates a script, and turns the numeric result the contract
specifies into the boolean or count a caller actually wants to reason about.

---

## The translation this class exists to do, and nothing more

`lock_seats.lua` returns `1` or `0`. `SeatLockStore.tryLock(...)` returns `true` or `false`. That
translation — a `Long` from Redis becoming a `boolean` in Java — is genuinely the entire value this
class adds over calling `redisTemplate.execute(script, keys, args)` directly, the way
`SeatLockScriptIT` (T143) does for its own, lower-level tests. `ReservationService` (T160), once it
exists, will call `tryLock` and `release` without ever touching a `RedisScript` or building a key by
hand — those details stay entirely inside this one class.

## The TRADEOFF worth stating plainly, not leaving implicit

`tryLock` runs inside `ReservationService`'s eventual database transaction, but it is not *part* of
that transaction — Redis has no concept of participating in a JDBC rollback. If the surrounding
transaction fails after this call has already succeeded, the seats stay held in Redis regardless,
until their TTL lapses on its own. Nobody is watching for that failure to free them early.

That's a real cost, and it's accepted on purpose rather than overlooked: the alternative ordering —
write the database first, evaluate the script second — fails in the *other* direction, which is worse.
A script failure after an already-committed database write would leave a `Reservation` row recorded
with no actual Redis hold behind it — a booking the system believes succeeded, with nothing stopping
someone else from taking the same seat. Seats briefly unavailable to everyone is a recoverable,
honest failure. A reservation recorded against a seat nobody is actually holding is the double-booking
this whole service exists to prevent. Given a choice between the two failure directions,
`contracts/inventory-consumer.md` picks the first one deliberately, and this class's own ordering is
what makes that the one actually available.

## Why `release` exists and is called from nowhere yet

`release(showId, seatIds, orderId)` is fully written and — once the script body exists — fully
testable, but nothing in this build step calls it. The `OrderCancelled` message that would trigger a
release has no publisher until step 5. Building it now means step 5 fills in a *caller*, not a
mechanism designed from scratch under whatever time pressure exists then.

## Verifying it

Compiled cleanly, confirmed via `javac` directly against the whole module's current state (still
blocked overall on `ReservationService`, T160). `SeatLockStore` itself has nothing left in it that
depends on an unwritten class — its own correctness is fully specified and will be exercised
end-to-end the moment `SeatLockScriptIT`'s tests can actually run, which still waits on the script
bodies themselves (T156).
