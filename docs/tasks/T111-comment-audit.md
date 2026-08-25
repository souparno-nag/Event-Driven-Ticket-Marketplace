# T111: an audit that found nothing to fix, and why that's the actual result

T111 asks for something a little unusual, compared to most tasks in this project: it doesn't build
anything. It's a pass over every `.java` file already written under `order-service/src/main/java` —
all 24 of them, across `domain`, `outbox`, `api`, `config`, and `service` — checking three things
against this project's own house style:

1. Does every comment explain **why** something is the way it is, rather than restating **what** the
   code in front of it already says?
2. Does every genuine tradeoff — a real alternative that was considered and rejected for a specific
   reason — carry the literal `TRADEOFF:` marker this codebase already uses consistently elsewhere
   (`OrderController`, `Order`, `OutboxRepository`, `OutboxRelay`, `JacksonConfig`,
   `ApiExceptionHandler`)?
3. Is there any genuinely non-obvious line — one whose reasoning wouldn't be clear to someone reading
   it cold — with no explanation attached to it at all?

## The result: zero changes

Every file checked out clean. No comment needed rewriting, no untagged tradeoff needed its marker
added, no unexplained non-obvious line turned up anywhere.

This isn't a surprise, and it's worth saying plainly why rather than letting a "nothing found" result
read like the audit wasn't thorough: this project has been holding itself to exactly this standard
the whole way through, one file at a time, as each one was written — not waiting for a single sweep
at the end to catch what accumulated. `OutboxRelay`'s own comments were themselves reviewed against
this exact checklist in T100, a dedicated task earlier in this same build step whose entire job was
walking that one file's comments against the project's own standards and fixing the one thing it
found short (a tradeoff that wasn't tagged). `ApiExceptionHandler` picked up its newest tradeoff
comment — the one explaining why `spring.mvc.problemdetails.enabled` was tried and reverted — written
with the `TRADEOFF:` tag from the moment it was first typed, in T105, a few tasks ago in this very
build step.

A "we checked everything and it was already right" result is a legitimate outcome of an audit task,
not a sign the audit was skipped. The alternative — inventing something to fix just so this task has
a visible diff — would be exactly the kind of unnecessary churn this project's own constraints
explicitly warn against.
