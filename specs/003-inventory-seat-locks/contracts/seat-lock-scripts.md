# Contract: the seat-lock scripts

**Feature**: `003-inventory-seat-locks`

This is the interface specification for the two files left unimplemented in this step. Everything
around them — the `DefaultRedisScript<Long>` beans, the calling method, the key builder, the schema,
the seeding, and the tests that judge them — ships working. The script bodies are written by the
developer.

A step-by-step guide, pitched at someone meeting the pattern for the first time, is delivered with the
implementation task in `docs/tasks/`. This document is the contract: what the scripts must guarantee,
not how to arrive at it.

---

## Why these two files are the exercise

Redis is single-threaded and runs a script to completion before serving any other command. That is the
only property that makes "check every seat is free, then take every seat" correct. Everything else in
this service is plumbing around that one fact, and the fact is four lines of Lua.

Get it wrong and the marketplace double-books, which is the failure a ticket system is judged on.

---

## `lock_seats.lua`

### Signature

```java
@Bean
DefaultRedisScript<Long> lockSeatsScript() { … }   // already wired
```

| | |
|---|---|
| `KEYS` | one seat key per requested seat: `seat:{showId}:{seatId}` |
| `ARGV[1]` | the order id claiming them |
| `ARGV[2]` | hold lifetime in milliseconds (120000) |
| returns | `1` — every key acquired; `0` — nothing acquired |

### Guarantees

| # | Guarantee | Requirement | Test |
|---|---|---|---|
| 1 | Returns `1` and sets every key when all keys are free | FR-004 | `SeatLockScriptIT#acquiresAllWhenAllFree` |
| 2 | Returns `0` and sets **nothing** when any key is held by another order | FR-005 | `SeatLockScriptIT#acquiresNothingWhenAnyHeld` |
| 3 | A key already holding **this** order's id counts as acquirable | FR-032 | `SeatLockScriptIT#reacquiresOwnKeys` |
| 4 | Every key set carries the TTL from `ARGV[2]` | FR-008 | `SeatLockScriptIT#setsTtlOnEveryKey` |
| 5 | Keys not named in `KEYS` are untouched | FR-005 | `SeatLockScriptIT#leavesOtherSeatsFree` |
| 6 | Under concurrent invocation for one seat, exactly one caller receives `1` | FR-004 | `ReservationContentionIT` |

### Traps this contract exists to prevent

**Setting as you go.** Checking and setting key by key in one pass means a script that returns `0` has
already taken some seats. They stay taken for 120 seconds and nobody owns them. Check **all** keys
first, in a complete pass, and only then set.

**Forgetting the self-owned case.** Guarantee 3 is what makes the script safe to re-run. Without it, a
retry after a transient error finds the seats it locked microseconds earlier and refuses itself — and
the refusal is `SEATS_ALREADY_HELD`, so the saga is told its seats are gone when they are its own.

**Using `KEYS` values to build other keys.** Everything the script touches must arrive in `KEYS`. This
is a Redis Cluster requirement and, more usefully here, it keeps the script's footprint obvious.

**Returning a boolean or a string.** Lua `false` converts to a Redis nil reply, and the bean is typed
`Long`. Return the number `1` or `0`.

---

## `release_seats.lua`

### Signature

| | |
|---|---|
| `KEYS` | the seat keys to release |
| `ARGV[1]` | the order id releasing them |
| returns | the number of keys actually deleted |

### Guarantees

| # | Guarantee | Requirement | Test |
|---|---|---|---|
| 1 | Deletes only keys whose value equals `ARGV[1]` | FR-006 | `SeatLockScriptIT#releasesOnlyOwnKeys` |
| 2 | Leaves a key owned by a different order untouched | FR-006 | `SeatLockScriptIT#doesNotStealAnotherOrdersSeat` |
| 3 | An already-absent key is not an error | FR-008 | `SeatLockScriptIT#releaseIsIdempotent` |
| 4 | Returns the count deleted | — | `SeatLockScriptIT#reportsReleasedCount` |

### The trap this one exists to prevent

**Unconditional `DEL`.** This is the classic distributed-lock bug and it is invisible in testing until
it is a double-booked venue:

1. Order A holds seat A1. Its hold lapses at 120 s and Redis frees the key.
2. Order B acquires A1 legitimately.
3. Order A's cancellation arrives late and releases A1 — deleting **B's** hold.
4. Order C acquires A1. Both B and C now believe they hold it.

Compare the value before deleting. That comparison is the whole reason this is a script rather than a
`DEL` call.

> **Scope note.** `release_seats.lua` is written and tested here, but nothing calls it in this step —
> the `OrderCancelled` message that triggers a release has no publisher until step 5. It is built now
> so that step fills in a body rather than designing a mechanism, and because writing it alongside
> `lock_seats.lua` is when the ownership check is most obviously necessary.

---

## Configuration

| Property | Default | Meaning |
|---|---|---|
| `inventory.hold.ttl-ms` | `120000` | Hold lifetime, passed as `ARGV[2]` (FR-008) |
| `inventory.hold.key-prefix` | `seat` | First segment of the key |
| `spring.data.redis.timeout` | `1s` | Command timeout — bounds the critical path (Principle IV) |
