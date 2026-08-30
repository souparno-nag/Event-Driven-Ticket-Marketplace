# T184 — the `inventory-service` section of `README.md`

**What this did:** updated the repository's own front door — the status banner, the file tree, a full
subsection describing this service, the test summary, and the roadmap table — to reflect that
`inventory-service` now exists, what it owns, and honestly, what one piece of it still doesn't do yet.

---

## Why the status banner names the one unfinished piece explicitly, rather than just saying "in progress"

A reader landing on this README for the first time has no way to know that `IdempotencyGuard`'s empty
body is a deliberate, on-purpose gap rather than an oversight, unless something tells them so directly.
Saying only "step 3 in progress" would leave that exact question open — is something broken, or is
something simply not written yet on purpose? Naming the one piece left, and linking straight to the
guide explaining why it's left that way, answers the question before anyone has to go looking through
`tasks.md` to find it themselves.

## Why the Redis key format gets three `redis-cli` commands instead of just the key shape

`seat:{showId}:{seatId} → orderId (TTL 120s)` on its own is accurate but abstract — it doesn't tell
anyone how to actually go look at one. The three commands this section gives — scan for every held
seat, read who holds one, and watch its remaining time count down — are exactly what T184's own task
asks for: a way to watch a hold appear and lapse, not merely a description of what a hold is shaped
like. Someone reading this section during an interview, or six months from now, can copy these three
lines and see the real thing happening rather than taking the shape on faith.

## Why the roadmap table gets a new status symbol rather than reusing "not started"

Every other unfinished step in the table genuinely has zero code written for it yet. Step 3 is a
different situation entirely — nearly everything is built and verified, with exactly one five-line
method standing between where things are now and a fully working saga hop. Marking it identically to
steps 4 through 11 would understate how much of this step is actually done; a distinct marker naming
the ONE remaining piece is what keeps the table an honest, at-a-glance summary of where the project
really stands.

## Verifying it

Read through as a reader arriving fresh would: the status banner states what's built and names the one
gap, the file tree and section both exist and link to real files that are actually there
(`docs/tasks/T174-idempotency-guard-guide.md`, `specs/003-inventory-seat-locks/contracts/inventory-consumer.md`,
`specs/003-inventory-seat-locks/contracts/seat-lock-scripts.md`, `specs/003-inventory-seat-locks/data-model.md`
— every link checked against the actual repository, not merely written from memory), and the roadmap
table's new row matches this checkpoint's own recorded state in `tasks.md`.
