--[[
  release_seats.lua — releasing a hold without stealing someone else's.

  The second of the two scripts left for the developer to write by hand
  (CLAUDE.md requirement 2; research.md R11). See
  docs/tasks/T156-seat-lock-scripts-guide.md for a beginner-level walkthrough,
  and contracts/seat-lock-scripts.md for the full contract this restates.

  SCOPE NOTE: nothing calls this script in this build step. The OrderCancelled
  message that would trigger a release has no publisher until step 5. It is
  written and tested here anyway — alongside lock_seats.lua, while the
  ownership check is most obviously necessary — so that step fills in a body
  rather than designs a mechanism from nothing.

  CONTRACT (contracts/seat-lock-scripts.md):

    KEYS      the seat keys to release
    ARGV[1]   the order id releasing them

    Deletes each key in KEYS whose CURRENT VALUE equals ARGV[1], and leaves
    every other key in KEYS completely untouched — including a key that is
    already absent, and including a key held by a different order.

    Returns the number of keys actually deleted.

  GUARANTEES THIS BODY MUST SATISFY (SeatLockScriptIT):
    1. releasesOnlyOwnKeys
    2. doesNotStealAnotherOrdersSeat
    3. releaseIsIdempotent
    4. reportsReleasedCount

  THE TRAP THIS SCRIPT EXISTS TO AVOID: an unconditional DEL. This is the
  classic distributed-lock bug, invisible in testing until it is a
  double-booked venue:

    1. Order A holds seat A1. Its hold lapses at 120s; Redis frees the key.
    2. Order B legitimately acquires A1.
    3. Order A's cancellation arrives late and deletes A1 unconditionally —
       taking B's hold with it.
    4. Order C acquires A1. B and C now both believe they hold it.

  Compare each key's value to ARGV[1] BEFORE deleting it. That comparison,
  done inside this single atomic script rather than as a separate GET then
  DEL from the calling Java code, is the entire reason this is a script and
  not a plain command.
--]]

-- Delete a key only if it still holds THIS order's id. A key already gone
-- (GET returns Lua's false) simply never equals ARGV[1], so it's skipped
-- with no special-casing -- that's what makes release idempotent for free.
local deleted = 0
for i = 1, #KEYS do
	local current = redis.call('GET', KEYS[i])
	if current == ARGV[1] then
		redis.call('DEL', KEYS[i])
		deleted = deleted + 1
	end
end

return deleted
