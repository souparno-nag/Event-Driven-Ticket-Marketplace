# T177 — `KafkaConsumerConfig`

**What this did:** wired up everything that decides what happens when this service can't process an
`order.created` message cleanly on the first try — how a genuinely unreadable message is told apart
from a readable one processing failed for some other reason, how many times a failure gets retried
before giving up, and exactly where a message ends up when this service gives up on it.

---

## Two completely different ways a message can fail, and why they need different handling

A message can be unreadable in two very different senses, and the whole point of this class is
treating them differently:

1. **The bytes themselves are broken.** Not valid JSON at all, or JSON that doesn't match
   `OrderCreated`'s shape. No number of retries fixes this — the bytes never change.
2. **The bytes are fine, but something ELSE went wrong while acting on them.** Redis or PostgreSQL was
   briefly unreachable, say. This is exactly the kind of failure that self-heals: try again in a
   moment, and it probably works.

Treating both the same way — either always retrying, or never retrying — gets one of the two cases
wrong every time. `ErrorHandlingDeserializer` is what lets the first case be recognised as early as
possible, before this service's own code even sees the message; `DefaultErrorHandler`'s exponential
backoff is what gives the second case room to self-heal without holding a whole partition hostage
forever.

## What "the dead-letter channel" actually is, in plain terms

Think of it as a shelf for mail nobody could deliver. When a message genuinely cannot be processed — a
shape this service will never understand, or a failure that kept happening even after several
retries — it gets moved to a separate channel (`order.created.DLT`) instead of being retried forever.
Nothing is lost; the message is still there, readable, and pulling everything genuinely broken onto one
shelf is exactly what lets the REST of the messages keep flowing normally instead of getting stuck
behind the one that will never succeed.

## The one gotcha that would have quietly lost messages if missed

Spring's own dead-letter tooling has a built-in default for naming that shelf: it would take
`order.created` and rename it `order.created-dlt`. This project already named and created its own
shelf back in an earlier build step: `order.created.DLT` — a different name. If this class had used
Spring's default instead of stating the real name explicitly, every dead-lettered message would have
been aimed at a channel that was never actually created — and since this environment deliberately
refuses to auto-create channels on the fly, that "recovery" attempt would itself fail, and the message
this whole mechanism exists to save would be gone for good. Overriding the destination is a small
piece of code with an outsized consequence if skipped.

## Why the dead-letter channel needed TWO delivery routes, not one

A message that failed because its bytes wouldn't parse is, at that point, still just raw bytes — there
is no properly-shaped `OrderCreated` object to publish, because building one is exactly what failed. A
message that failed AFTER successfully parsing (an unrecognised version, or an exhausted retry) is the
opposite: a fully-formed `OrderCreated` object, which needs to be turned into JSON text to be published
again. One single publishing route configured for only one of those two shapes would throw trying to
handle the other, so this class builds two routes and lets Spring's own dead-letter machinery pick
whichever one actually matches what it's holding.

## Verifying it

This class's real proof came from running `UndecidableRequestIT` (T169) for real: `unknownVersionGoesToDlt`
and `dlttedAtAttemptLimit` both went from failing (nothing existed to route anything anywhere) to
passing once this class and `OrderCreatedListener` (T178) existed together — direct evidence that both
the "unrecognised shape" route and the "retries exhausted" route land where they're supposed to, against
a real broker, not merely by reading the configuration and trusting it looks right.
