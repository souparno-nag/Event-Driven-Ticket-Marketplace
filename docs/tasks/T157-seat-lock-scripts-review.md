# T157 — reviewing the T156 scripts

**What this did:** checked the two script bodies written in T156 against every one of the ten
guarantees `contracts/seat-lock-scripts.md` states, and against this project's own comment standards,
per the task's own instruction: keep it if it passes and reads well, rewrite it only if it doesn't.

---

## The ten guarantees, checked one by one

`lock_seats.lua` (six guarantees):

| # | Guarantee | Result |
|---|---|---|
| 1 | All free → returns 1, sets every key | ✅ `acquiresAllWhenAllFree` |
| 2 | Any held → returns 0, sets nothing | ✅ `acquiresNothingWhenAnyHeld` |
| 3 | A key already held by the same order counts as acquirable | ✅ `reacquiresOwnKeys` |
| 4 | Every key set carries the TTL | ✅ `setsTtlOnEveryKey` |
| 5 | Keys not named in `KEYS` are untouched | ✅ `leavesOtherSeatsFree` |
| 6 | Exactly one caller wins under real concurrency | ✅ `ReservationContentionIT` — 20/20 repetitions, 10 granted / 990 refused every time |

`release_seats.lua` (four guarantees):

| # | Guarantee | Result |
|---|---|---|
| 1 | Deletes only keys whose value equals the caller's order id | ✅ `releasesOnlyOwnKeys` |
| 2 | Leaves another order's key alone | ✅ `doesNotStealAnotherOrdersSeat` |
| 3 | An absent key is not an error | ✅ `releaseIsIdempotent` |
| 4 | Returns the count deleted | ✅ `reportsReleasedCount` |

All ten hold. `SeatLockScriptIT` itself is 9 tests (guarantee 6 is deliberately tested through real
concurrency in `ReservationContentionIT` instead, per that file's own Javadoc — a single-threaded test
method can't tell a correct lock from one that merely got lucky).

## Comment standards

Both files already carry a full header (written in T152) restating the contract, the guarantees, and
the specific trap each script exists to avoid — that part predates this task and needed no change. The
bodies added in T156 keep to the project's own rule: every comment explains **why** a line exists, not
what it does.

- `lock_seats.lua`'s two-pass split has a comment on each pass explaining why the split exists (a
  single-pass version would leave orphaned holds on a refusal) rather than merely labeling "first loop"
  / "second loop".
- `release_seats.lua`'s idempotency comment explains why an absent key needs no special-case code
  (`GET` on a missing key returns Lua's `false`, which never equals a real order id), rather than just
  stating that it's idempotent.

Both bodies are four to nine lines of straightforward, flat Lua — two loops each, no nested
abstraction, no cleverness beyond what the guarantee requires. This matches the project's own
"flat and obvious beats clever" standard, and there's no tradeoff worth a `TRADEOFF:` comment: the
two-pass shape isn't a design choice weighed against an alternative, it's the only shape that satisfies
guarantee 2 (nothing set on refusal) at all.

## Conclusion

Kept as written — no rewrite needed. Both scripts pass every guarantee for real, against a real Redis,
and their comments already explain the reasoning a reader would otherwise have to reconstruct.
