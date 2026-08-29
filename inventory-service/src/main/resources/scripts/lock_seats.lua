--[[
  lock_seats.lua — the atomic all-or-nothing seat hold.

  This is the one piece of this service left for the developer to write by hand
  (CLAUDE.md requirement 2; research.md R11). Everything around it — the
  DefaultRedisScript<Long> bean that loads this file, the calling method, the
  key format, the schema, the seeding, and the tests that judge it — ships
  working. This file is the contract, not a tutorial; a beginner-level guide to
  arriving at a correct body lives in docs/tasks/T156-seat-lock-scripts-guide.md.

  CONTRACT (contracts/seat-lock-scripts.md):

    KEYS   one seat key per requested seat, each shaped seat:{showId}:{seatId}
    ARGV[1]   the order id claiming them
    ARGV[2]   hold lifetime in milliseconds (120000)

    Returns 1 and sets EVERY key to ARGV[1] with a TTL of ARGV[2] milliseconds,
    if and only if every key in KEYS is either currently unset, or already set
    to ARGV[1] itself (guarantee 3 — a retry must not refuse itself).

    Returns 0 and sets NOTHING — not even the keys that were free — if any key
    in KEYS is currently set to a value other than ARGV[1].

  GUARANTEES THIS BODY MUST SATISFY (SeatLockScriptIT):
    1. acquiresAllWhenAllFree
    2. acquiresNothingWhenAnyHeld
    3. reacquiresOwnKeys
    4. setsTtlOnEveryKey
    5. leavesOtherSeatsFree
    6. (ReservationContentionIT) — exactly one caller wins under real concurrency

  THE TRAP THIS SCRIPT EXISTS TO AVOID: checking and setting one key at a time
  in the same pass. A script that does that has already taken some seats by
  the moment it discovers a later one is unavailable — they stay taken for the
  full TTL, held by an order that was refused. Check ALL keys first, in a
  complete pass with nothing set yet; only once every key has been confirmed
  free-or-own-order does any SET happen.

  Return the number 1 or 0 — never a boolean. Lua's `false` becomes a Redis nil
  reply, and the Java side expects a Long.
--]]
