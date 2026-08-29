# T143 — Specifying the nine guarantees of the Lua scripts, before either exists

**What this task did:** wrote `SeatLockScriptIT`, testing the nine guarantees
`contracts/seat-lock-scripts.md` states for `lock_seats.lua` and `release_seats.lua` — against
classes and files three separate later tasks have not yet produced.

---

## Confirmed to fail, for the right reason

```text
19 errors, all: cannot find symbol: SeatKey
```

Verified with `javac` directly. Every single error traces to `SeatKey` (T142's own subject, still
unwritten) — nothing about the `RedisScript<Long>` fields or their `@Qualifier` annotations fails to
compile, because `RedisScript` is a real Spring Data Redis type that already exists; only the *bean
names* `lockSeatsScript`/`releaseSeatsScript` those qualifiers name are still missing, and a missing
bean name is a runtime failure Spring would report, not something `javac` can see at all. That
distinction matters: it means this file is staged to fail in exactly three separate ways as three
separate later tasks land, each removing one obstacle at a time —

| Stage | What blocks it | Resolved by |
|---|---|---|
| 1 | Does not compile at all | T151 (`SeatKey`) |
| 2 | Compiles; Spring context fails to start (`no bean named lockSeatsScript`) | T153 (`SeatLockScripts`) |
| 3 | Boots; every test fails against empty `.lua` stubs | T156 (the developer implements the scripts) |

— which is precisely what tasks.md's own note describes: "They fail until T152–T157 land, and the Lua
tests fail until T155."

## Testing the raw scripts, not the higher-level API

Every assertion calls `redisTemplate.execute(script, keys, args)` directly, rather than going through
whatever boolean/count API `SeatLockStore` (T154) eventually wraps them in. `contracts/seat-lock-
scripts.md` states its nine guarantees entirely in terms of `KEYS`, `ARGV`, and a numeric return value
— testing at exactly that level is what lets a failure be traced straight back to the four lines of
Lua responsible, with no translation layer in between to obscure which side of the boundary broke.

## The nine method names, taken directly from the contract's own table

`acquiresAllWhenAllFree`, `acquiresNothingWhenAnyHeld`, `reacquiresOwnKeys`, `setsTtlOnEveryKey`,
`leavesOtherSeatsFree`, `releasesOnlyOwnKeys`, `doesNotStealAnotherOrdersSeat`, `releaseIsIdempotent`,
`reportsReleasedCount` — every one of these names is already written into
`contracts/seat-lock-scripts.md`'s own "Test" column. Using them verbatim, rather than inventing
similar-sounding names, is what makes the contract document and this file the same specification read
from two directions.

**Guarantee 6 — exactly one caller wins under real concurrent invocation — is deliberately absent.**
That one belongs to `ReservationContentionIT` (T144): a single-threaded test method calling a script
twice in sequence proves nothing about what happens when a thousand callers race for real, and
research.md R10 is explicit that the channel and any in-process test loop both cap apparent
concurrency far below what actually distinguishes a correct all-or-nothing hold from a broken one that
merely got lucky.

## Each test proves the specific trap it's named for

`acquiresNothingWhenAnyHeld` doesn't just check the return value is `0` — it asserts the two seats
that *were* free remain completely absent afterward, which is the assertion that actually catches a
script that checks and sets key-by-key rather than in one atomic pass (the exact failure mode the
contract's own "Traps this contract exists to prevent" section names first). `doesNotStealAnotherOrdersSeat`
reproduces the contract's own four-step walkthrough of the classic distributed-lock bug almost
verbatim — order A holds a seat, order B legitimately takes over the key after A's late release should
have found it already reassigned, and the assertion is that B's ownership survives A's release attempt
untouched.
