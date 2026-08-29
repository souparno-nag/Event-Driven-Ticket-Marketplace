# T153 — `SeatLockScripts`

**What this task did:** wrote the two `DefaultRedisScript<Long>` beans that load `lock_seats.lua` and
`release_seats.lua` from the classpath — the piece `SeatLockScriptIT` (T143) has been waiting for
since it was written against bean names that didn't exist yet.

---

## Bean names are the actual contract here, not an implementation detail

```java
@Bean
public DefaultRedisScript<Long> lockSeatsScript() { ... }
@Bean
public DefaultRedisScript<Long> releaseSeatsScript() { ... }
```

`RedisScript<Long>` alone is not a unique type in this application context — both beans share it. Every
consumer of either script — `SeatLockStore` (T154) and every test in `SeatLockScriptIT` (T143) —
disambiguates by `@Qualifier("lockSeatsScript")` or `@Qualifier("releaseSeatsScript")`, and those
qualifier strings are literal bean names that have to match these method names exactly. T143 committed
to those names before this class existed to provide them; getting a bean name wrong here wouldn't be a
compile error anywhere, only a runtime "no bean named X" failure the moment the context tries to wire
`SeatLockStore` or a test together.

## Why `Long`, stated once, here

Both scripts are still empty stubs (T152) that return nothing at all right now — this class's own
correctness doesn't depend on either body being written yet. What it *does* lock in ahead of that body
being written is the result type: `Long`, not `Boolean` or `String`. Lua's `false` converts to a Redis
nil reply on the wire, which has no sensible mapping to a Java `Boolean` — Spring Data Redis would
either fail that conversion outright or hand back `null`, and either turns "the hold failed" into an
exception or a `NullPointerException` somewhere downstream instead of the plain `0` both scripts'
contracts specify. Declaring `Long` here, once, is what turns "the script body returns a boolean" from
a runtime surprise discovered under load into something the developer writing T156 finds out about
immediately, the first time either test runs.

## Verifying it

Compiled cleanly against `SeatLockScriptIT` — confirmed with `javac` directly, since the module as a
whole still can't compile until `ReservationService` (T160) exists. The `RedisScript<Long>` field
declarations and their `@Qualifier` annotations, which previously compiled without complaint on their
own (the missing piece was always `SeatKey`, never these types), now have real beans behind them to
resolve at runtime — though "resolve correctly and actually work" still waits on `SeatLockStore` (T154)
and the script bodies themselves (T156).
