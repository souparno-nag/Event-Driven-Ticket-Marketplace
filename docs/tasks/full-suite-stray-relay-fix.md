# Why the full test suite was failing, and what two real bugs were hiding in it

T099 and T100 were finished and verified by running just the five tests that specifically judge the
outbox relay. Running the *entire* test suite together — every test written so far in this build
step, in one build — turned up two more failures. Both turned out to be genuine bugs, not flaky
tests, and both trace back to the same underlying fact about this project: `OutboxRelay` is
`@Scheduled`, so it is **always running in the background**, in every single test's Spring context,
whether that test has anything to do with the relay or not.

## Bug 1: a background thread stealing a test's own stubbed behavior

`OrderAcceptanceIT`'s `RollbackWhenTheOutboxWriteFails` test replaces the real database repository
with a fake one (a "mock") that's told to throw an exception the moment anything tries to save to it,
so the test can check that a failed outbox write correctly rolls the whole order back. It kept
failing with "expecting code to raise a throwable" — meaning the fake repository *didn't* throw, even
though the test had just told it to.

The reason: `@EnableScheduling` means the relay's background poller is genuinely alive in *this*
test's context too, and it was calling a method on that same fake repository from a separate thread,
at the exact moment the test was setting up its stub. The library used for building fakes (Mockito)
keeps track of "the last thing that was called on this mock" in a single slot so it knows what the
*next* line of code (`.thenThrow(...)`) should apply to — and that slot isn't safe to write to from
two threads at once. The background thread's call landed in that slot right as the test's own call
was about to, so the exception got attached to the *wrong* method, and the one the test actually cared
about never got its stub at all.

The first attempt at a fix — telling the scheduler to wait a very long time between runs — didn't
work, because of a detail in how Spring's `@Scheduled` annotation behaves: the setting that controls
the *gap between runs* has no effect on how soon the *very first* run happens. That first run fires
almost immediately no matter what, which is exactly the moment this test's own setup code was running.
The real fix needed a second setting — one that also controls the delay before that very first run —
so both the gap and the very first run could be pushed safely out of the way for this test.

## Bug 2: a stray background relay publishing to the wrong Kafka, and marking rows sent that a test never saw

Some tests need a real Kafka message broker to test against, and get one by starting a temporary,
throwaway broker just for that test. Other tests — the ones that only touch the database — don't
bother starting a broker at all, since they never need to send anything.

But because `OutboxRelay`'s scheduler is *always* running, every one of those database-only tests
*also* has a live relay quietly polling in the background — and since nobody told it about a
throwaway broker, it falls back to whatever address the real application would use: the project's
own long-running local Kafka broker (the one started by `make up`, not a test one). That relay is
still reading from the exact same shared database table every other test uses.

The claiming code has no way of knowing "this row belongs to some other test" — it just hands out
whatever's waiting to be sent to whichever relay asks first. So a heavy test that inserts a thousand
rows and races its own three worker threads to publish them could lose a slice of those rows to one
of these unrelated, database-only tests' stray relays — which would claim a few, genuinely send them
(just to the *wrong* broker), and mark them as sent. The database would honestly say every row was
published, because it was — just not to the broker the original test was watching. No amount of
waiting longer would ever make those particular messages show up, because they were never sent to
where anyone was looking.

This was confirmed directly: a test's own consumer sat exactly caught up to the end of its broker's
message log for far longer than it should have, while the database simultaneously reported every row
as published — proof the missing messages genuinely existed, just somewhere else. Checking the logs
from the database-only tests confirmed it: their relays really were connecting to the project's real
local Kafka broker, not a throwaway one.

The fix: every test that only needs a database, not a broker, now also tells its relay to wait a very
long time before doing anything (both the gap between runs and the delay before the first one — same
two settings as Bug 1). One test still needs to be left alone: `OutboxRestartRecoveryIT` exists
specifically to prove that a restarted service picks its work back up automatically, with no manual
step, so it's the one place real scheduling has to stay switched on.

Getting this right needed one more discovery: Spring's testing framework does not reliably let one
test class "undo" a setting an earlier, more general test class already fixed for the whole family —
whichever one registers it first quietly wins, even if a more specific class tries to change it back
later. That ruled out the simplest approach (put the suppression on the one shared class everything
extends, and have the one test that needs real scheduling turn it back on). Instead, the suppression
lives on two small, purpose-built classes — one for the database-only tests, one for the
relay-driving Kafka tests — and the one test needing real scheduling simply isn't a descendant of
either, so it never inherits a setting it would need to fight with.

## What this means for trusting the test suite

Both bugs were only visible when the *whole* suite ran together, never when the relay-specific tests
ran on their own — because both depended on some *other*, unrelated test's background relay being
alive at the same time. A green result from running only the tests that seem relevant to a change is
not the same guarantee as a green result from the whole suite; this pair of bugs is exactly the kind
of thing that gap can hide.
