# T172 — scaffolding `IdempotencyGuard`

**What this did:** created the class, its one collaborator, and the exact method signature the
listener will call — with the method's body deliberately left throwing an obvious, loud error, per
CLAUDE.md's own instruction that this piece is a developer exercise.

---

## Why the body throws an exception rather than containing a guess at the real logic

CLAUDE.md asks for this method's body to be left as a stub for a human to fill in, the same way
`lock_seats.lua` and `release_seats.lua` were left as stubs earlier in this build step. Lua's own
version of "not implemented yet" was simply an empty file — Redis runs nothing and returns `nil`,
which surfaces as an obvious wrong answer the moment anything calls it. Java has no equivalent of "a
method that compiles but contains nothing at all" — a method body has to do SOMETHING. The closest
faithful equivalent is `throw new UnsupportedOperationException(...)`: the class exists, the method
signature exists, everything compiles and wires together correctly, but calling the method fails
loudly and immediately with a message pointing at exactly what still needs to be written. Anything else
— guessing at a real implementation, or quietly returning `true` or `false` as a placeholder — would
either finish the exercise for the next developer or, worse, hide a wrong answer behind something that
looks like it works.

## Why the ordering warning lives in this file's own Javadoc, not only in the guide

The guide (T174, delivered next) walks through the reasoning slowly, for someone meeting the pattern
for the first time. This class's own Javadoc restates the one fact that matters most concretely: the
guard must run BEFORE the Redis hold is attempted, never after, because the seat-lock script's own
guarantee 3 — a legitimate retry re-acquiring its own seats without refusing itself — means a
redelivery reaching Redis before this guard runs would succeed at the lock and then attempt a genuinely
duplicate reservation. Whoever eventually calls this method from `OrderCreatedListener` (T178) will
read this class's Javadoc at the call site, not necessarily the separate guide file, so the warning
belongs here too.

## Verifying it

The whole module still compiles cleanly with this stub in place: `mvn test-compile`. Nothing calls
this method yet, so nothing exercises the thrown exception — that's expected; `IdempotencyIT` (T168)
is what will call it, once `OrderCreatedListener` (T178) exists to wire it in.
