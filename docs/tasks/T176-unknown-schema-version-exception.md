# T176 — `UnknownSchemaVersionException`

**What this did:** created the one exception type this service throws when a message's declared
shape version isn't one it knows how to read — the signal `KafkaConsumerConfig` (T177) uses to send
that message straight to the dead-letter channel instead of retrying it.

---

## Why a version travels on every message at all

Different services in this project get deployed at different times. During any rolling deployment,
it's completely normal for an OLDER version of inventory-service to still be running while a NEWER
version of order-service has already started publishing messages — possibly in a slightly different
shape, if a field was ever added. The `schemaVersion` number on every message is how a consumer can
tell "I recognise this shape" from "I don't," rather than guessing and possibly misreading a message
it was never built to understand.

## Why "unrecognised" is a different concern from "obviously invalid"

`OrderCreated` itself already refuses to exist with a `schemaVersion` below 1 — a basic sanity check
every message shape shares, enforced the moment the object is built. But that check has no way to know
which versions THIS PARTICULAR consumer currently understands; it only knows a version number should be
a positive integer. Today, inventory-service understands exactly version 1. A message declaring version
2 would pass `OrderCreated`'s own sanity check without any trouble — and still be something this service
has no code path for. That's a decision only the consumer doing the interpreting can make, which is why
it happens in `OrderCreatedListener`, not in the shared `OrderCreated` record every service compiles
against.

## Why this needed its own exception type rather than reusing a generic one

The whole point of this exception is to be routed differently from an ordinary bug: Kafka's error
handler decides whether to retry a failure by checking what TYPE of exception was thrown. If this used
a common type like `IllegalArgumentException` — a type ordinary programming mistakes elsewhere in this
service might also throw — the error handler would have no way to tell "a message with a shape I'll
never understand" apart from "a bug that happens to throw the same kind of exception," and would
either retry a message that will never succeed no matter how many times it's tried, or worse, skip
retrying a genuine bug that deserved another chance. A dedicated type means exactly one situation is
ever classified this way.

## Verifying it

Compiles cleanly on its own — this class has no behaviour to test in isolation, only a message. Its
real test is `UndecidableRequestIT#unknownVersionGoesToDlt` (T169), which publishes a message with a
deliberately unrecognised version and confirms it reaches the dead-letter channel quickly, not after
exhausting a retry schedule.
