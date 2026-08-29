# A guide to writing `lock_seats.lua` and `release_seats.lua`

These are the two files in this entire build step you write by hand. Everything around them — the
schema, the entities, the beans that load these files, the calling method, the seeding, and every test
that will judge them — already exists. This document is not a spec (that's
`contracts/seat-lock-scripts.md`, and you should have it open alongside this). It's a walk-through:
what the pattern is, why Redis specifically is the tool for this job, and what tends to go wrong,
aimed at someone meeting Lua for the first time.

---

## 1. Why this needs to be a script at all

Picture the naive version of "hold three seats": for each seat, check if it's free, and if it is, take
it. In plain Java against Redis, that's roughly:

```java
for (String seat : seats) {
    if (redis.get(seat) == null) {
        redis.set(seat, orderId);
    }
}
```

This looks reasonable and is completely wrong the moment two buyers want overlapping seats at the same
instant. Say buyer A and buyer B both want seats A1 and A2, at the same millisecond. Both check A1: it's
free. Both check A2: it's free. Both now believe they can take both seats — and both do. There is a gap
between "I looked" and "I took it," and every other contender is doing the exact same thing in that
gap. No amount of clever Java-side locking closes this, because the two clients are two separate
processes (or even two threads on two different machines) that Redis itself has no idea are racing.

Redis has one property that makes this solvable: it is single-threaded, and when you ask it to run a
Lua script, it runs that **entire script to completion** before it does anything else — before serving
any other client's command, before even starting the next thing in its own queue. If the "check
everything, then take everything" logic lives *inside* the script rather than in your Java code making
several separate Redis calls, there is no gap for a second buyer to land in. That's the whole reason
these two files are Lua instead of Java: it's not a stylistic choice, it's the only place this specific
kind of atomicity is available.

---

## 2. What you've been given, and how it fits together

**The two files** live at `src/main/resources/scripts/lock_seats.lua` and `release_seats.lua`.
Right now each one contains only a comment describing its contract — no executable code at all. That's
what you're filling in.

**`SeatLockScripts`** loads each file into a `DefaultRedisScript<Long>` Spring bean. You don't touch
this class — it's already correct and already wired up.

**`SeatLockStore`** is the Java method that actually calls your script: it builds the Redis keys (via
`SeatKey`, also already written), evaluates the script, and translates the number your script returns
into a Java `boolean` or a count. You don't touch this either.

**`SeatKey.of(showId, seatId)`** builds the key format your script will see arrive in `KEYS`:
`seat:{showId}:{seatId}`.

**`SeatLockScriptIT`** is the test file that will tell you whether your script bodies are correct. It
calls the raw scripts directly — the same way `SeatLockStore` does — and checks each guarantee below
one at a time.

---

## 3. `lock_seats.lua`, guarantee by guarantee

### The signature

| | |
|---|---|
| `KEYS` | one seat key per requested seat — `KEYS[1]`, `KEYS[2]`, ... |
| `ARGV[1]` | the order id claiming them, as a string |
| `ARGV[2]` | hold lifetime in milliseconds, as a string (Lua receives every `ARGV` value as a string, even though it started life as a Java `long`) |
| returns | the number `1` if every key was acquired, or `0` if nothing was |

### What "acquired" actually means for one key

A single key counts as acquirable if **either**:
- it doesn't exist yet (nobody holds this seat), **or**
- it already holds the exact value in `ARGV[1]` (this order already holds it — see below for why this
  matters)

If every key in `KEYS` meets one of those two conditions, the script must set all of them to
`ARGV[1]`, each with a TTL of `ARGV[2]` milliseconds, and return `1`. If even one key fails both
conditions — held, and held by somebody else — the script must set **nothing at all** and return `0`.

### Guarantee 3 — the self-owned case — deserves its own explanation

It would be reasonable to assume "acquired" should only mean "was free." Here's why that's not quite
right: imagine your Java code successfully runs this script, the seats are taken — and then, a moment
later, something else fails (the database write, say), and the whole operation gets retried from the
top. On the retry, this script runs again, with the exact same order id, against the exact same seats
— which it *itself* set moments ago. If "acquired" only meant "was free," this retry would see its own
seats as held (by itself!) and refuse — telling the order its own seats are unavailable. Treating a key
already holding this order's own id as acquirable is what makes a retry safe rather than
self-destructive.

### The one thing that will bite you if you get the ORDER of operations wrong

Do not check-and-set one key at a time in a single loop. If you do, and the seat sequence is A1, A2,
A3, and A3 turns out to be held by someone else, your script has *already* set A1 and A2 by the time
it discovers A3 is a problem. Those two seats are now held by an order that was refused, for the next
120 seconds, and nobody owns them.

The fix is to do this in two completely separate passes: first, loop over every key in `KEYS` and
check whether it's acquirable, without setting anything. Only after confirming *all* of them pass, loop
over `KEYS` again and actually set each one.

### A Redis command cheat-sheet for this script

- `redis.call('GET', KEYS[i])` — read a key's current value, or Lua's `false` if it doesn't exist
- `redis.call('SET', KEYS[i], ARGV[1], 'PX', ARGV[2])` — set a key with a millisecond TTL in one call
- `return 1` / `return 0` — the numbers, not `true`/`false`. Lua's `false` becomes a Redis nil reply,
  and the Java side is expecting a `Long`. Returning a boolean will not throw an obvious error — it
  will just quietly break, likely as an unexpected `null` in Java.

---

## 4. `release_seats.lua`, guarantee by guarantee

### The signature

| | |
|---|---|
| `KEYS` | the seat keys to release |
| `ARGV[1]` | the order id releasing them |
| returns | the number of keys actually deleted |

### The bug this script exists to prevent

This is worth reading slowly, because it's the kind of bug that passes every casual test and then
quietly double-sells a seat in production:

1. Order A holds seat A1. Its 120-second hold lapses; Redis frees the key on its own.
2. Order B comes along and legitimately acquires A1.
3. Order A's cancellation — which was already in flight before the hold lapsed — finally arrives, and
   naively deletes A1 unconditionally.
4. Order A's delete just took **B's** hold, not A's. The key is gone. Order C now acquires A1 too.
5. B and C both believe they hold the same seat.

The fix: before deleting a key, check that its **current value** still equals `ARGV[1]` — the order
asking to release it. Only delete keys that pass that check. A key held by someone else, or a key
that's already gone, is left completely alone.

### A Redis command cheat-sheet for this script

- `redis.call('GET', KEYS[i])` — read the current owner
- compare it (as a Lua string) to `ARGV[1]`
- `redis.call('DEL', KEYS[i])` — delete only if the comparison matched
- keep a running count of how many you actually deleted, and `return` that number at the end

### Idempotency, almost for free

If a key doesn't exist at all (`GET` returns Lua's `false`), that simply won't equal `ARGV[1]`, so your
comparison naturally skips it — no special-case code needed. Releasing something already gone should
just not count toward the total, not raise an error.

---

## 5. Verifying it

```bash
./mvnw -pl inventory-service -am verify -Dit.test=SeatLockScriptIT -Dfailsafe.failIfNoSpecifiedTests=false
```

Nine tests, one per guarantee, named exactly as they appear in `contracts/seat-lock-scripts.md`'s own
tables. All nine need to be green — that's the actual definition of "done" here, not whether the code
looks right to you.

One thing worth knowing in advance: this same command may also print connection errors from other
files in the module that don't compile yet, if any remain — those are unrelated and expected at this
stage; only `SeatLockScriptIT`'s own result matters for this task.
