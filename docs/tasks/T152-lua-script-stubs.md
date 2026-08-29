# T152 — The two Lua files, as empty contracts

**What this task did:** created `lock_seats.lua` and `release_seats.lua` at
`inventory-service/src/main/resources/scripts/` — each containing nothing but a header comment
stating its own contract. Neither file has an executable line of code in it. That's the deliverable,
not an omission.

---

## Why an empty file is the correct thing to commit here

CLAUDE.md's own instruction for this exact spot is explicit: scaffold everything around the two
scripts, but leave their bodies for the developer to write, because working out the four-line
atomic check-then-take by hand is the one piece of this build step actually worth learning rather than
reading finished. `research.md` R11 records the same decision independently. Writing a plausible-looking
body now — even a "temporary" one meant to be replaced — would defeat the entire point: there would be
nothing left to actually work out.

## What the header comments actually contain

Not a placeholder saying "TODO: implement this" — the full contract, restated inside the file itself:
the exact `KEYS`/`ARGV` shape, what "acquired" means for a single key (including the self-owned case
guarantee 3 requires), the return value's exact meaning, and the specific trap each script exists to
avoid (checking-and-setting one key at a time for `lock_seats.lua`; an unconditional `DEL` for
`release_seats.lua`). Someone opening either file with zero other context still finds the complete
specification for what it needs to do — the same information `contracts/seat-lock-scripts.md` carries,
restated at the point where it will actually be read while writing the body.

## `release_seats.lua`'s scope note, carried forward faithfully

The header comment repeats something worth not losing in translation: nothing calls this script in
this build step. The `OrderCancelled` message that would trigger a release has no publisher until step
5. It's specified and tested now anyway, alongside `lock_seats.lua`, because writing the two together
is when the ownership-check trap in `release_seats.lua` is most obviously necessary — having just
reasoned through why `lock_seats.lua` cannot check-then-set naively, the identical shape of mistake in
`release_seats.lua` (delete unconditionally) is much easier to see coming.

## Verifying it

There is nothing to execute yet — an empty Lua file, loaded by `SeatLockScripts` (T153), evaluates to
nothing and satisfies no guarantee. What *can* be confirmed now is that both files exist at exactly the
classpath locations `SeatLockScripts` will load (`scripts/lock_seats.lua`, `scripts/release_seats.lua`),
and that neither one accidentally contains working Lua that would let a test pass by accident before
the actual exercise (T156) has happened.
