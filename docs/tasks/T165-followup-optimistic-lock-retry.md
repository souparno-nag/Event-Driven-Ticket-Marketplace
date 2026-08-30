# Follow-up to T165 — a genuine concurrency bug T165's own changes made reliably visible

**What this did:** implemented CLAUDE.md's own optimistic-concurrency requirement — "retry once on
`OptimisticLockingFailureException`" — which had never actually been built anywhere in this service
despite `ReservationVersionIT` (T148)'s own Javadoc describing exactly that behaviour as already
expected. Found because T165's changes, added earlier in this same session, made a rare timing
collision happen every single time instead of almost never.

---

## What two people racing for the same stale seat actually collide over

`ReservationVersionIT` plants a reservation whose hold has already lapsed, then has two brand-new
orders both try to book the very same seat at once. Both threads discover the same stale row and both
try to retire it — mark it `EXPIRED`, release its seat — before either one attempts the real Redis
hold. Only one of those two retirements can actually win: the `@Version` column on `reservations`
exists specifically so that whichever thread's update reaches the database second gets told "someone
already changed this row since you read it" rather than silently overwriting the first thread's work.

## The bug: nothing was listening for that "someone already changed it" signal

Spring translates that collision into an `OptimisticLockingFailureException`. Before this fix, nothing
in `ReservationService` ever caught it. It simply propagated all the way out of the losing thread,
uncaught — which, in the test, meant that thread's result never made it into the list of outcomes being
checked at all. The test's own assertion — "exactly one attempt should come back refused" — then found
zero refused attempts, because the losing attempt hadn't come back with an outcome of any kind; it had
crashed.

## Why this had never been seen before, and why it suddenly became impossible to miss

Two threads only actually collide on this check if their two attempts to retire the exact same row
happen to reach the database within a narrow enough window of each other. Before this session's
earlier work on User Story 2 (T165), `decide(...)` went almost straight into that retirement step.
T165 added two quick, harmless-looking lookups first — does the show exist, do the seat labels exist —
before ever reaching the retirement code. Those two lookups take a small, consistent amount of time for
both racing threads alike, which had the side effect of synchronising the two threads' arrival at the
retirement step far more closely than before. A collision that used to be rare enough to never actually
show up in this session's many earlier test runs became, after that change, the reliable outcome every
single time the test ran.

This is worth stating plainly: T165's own change didn't create the bug. The missing retry logic was
already a real gap, exactly matching a requirement CLAUDE.md had stated from the start and a behaviour
`ReservationVersionIT`'s own T148 Javadoc already described as expected. T165's change simply made an
already-real bug impossible to keep not noticing.

## The fix, and why it can't just be "catch the exception and try again in the same method"

The natural first instinct — wrap the existing `@Transactional` method's body in a try/catch and call
itself again on failure — does not actually work here, for a reason worth understanding rather than
memorising: once Hibernate raises this exact kind of exception during a flush, the persistence context
that experienced it is left in a state where continuing to use it for further work in the *same*
transaction is not safe. The retry genuinely needs a brand new transaction and a brand new persistence
context, so that reading the row again actually sees the winner's now-current version rather than the
same stale copy that just caused the conflict.

`ReservationService` now builds a `TransactionTemplate` from the ordinary auto-configured
`PlatformTransactionManager` instead of annotating `decide(...)` with `@Transactional`. The whole unit
of work — decide the outcome, write the outbox row — moved into a private `decideAndRecord(...)`
method that `transactionTemplate.execute(...)` runs inside a fresh transaction. `decide(...)` calls it
once; if that throws `OptimisticLockingFailureException`, it calls `transactionTemplate.execute(...)`
a second time, which opens a second, completely independent transaction to retry in. If that second
attempt also fails, the exception is allowed to propagate — "retry once," not "retry until it works,"
matching CLAUDE.md's own wording and `ReservationVersionIT`'s own note that a *second* failure is
meant to "surface as a processing failure," not be silently retried forever.

## Verifying it

Before the fix: `ReservationVersionIT` failed 5 times in 5 runs, every single time with the same
uncaught `ObjectOptimisticLockingFailureException`. After the fix: 5 runs in a row passed. A temporary
diagnostic print in the retry branch (added, checked, then removed) confirmed the retry path is not
merely present but actually being exercised on every run — the two threads really are colliding every
time, and the retry is what turns that collision into the correct, expected outcome instead of a lost
result.

```text
mvn clean verify (whole module), twice in a row: 48 tests, 0 failures, 0 errors, both times
```
