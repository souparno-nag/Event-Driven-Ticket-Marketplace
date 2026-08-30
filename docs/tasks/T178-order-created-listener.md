# T178 — `OrderCreatedListener`

**What this did:** wrote the one method in the entire service where a real `order.created` message
actually becomes a call into the booking decision — the class every other piece of this build step
exists to feed into.

---

## Why this class does almost nothing on its own

Reading this class, the temptation is to expect it to do a lot: after all, it's the entry point for
the project's very first message consumer. It actually does two small things: check whether the
message's declared shape is one this service understands, and if so, hand it to
`ReservationService`. Everything that actually matters — the idempotency check, the seating-plan
lookups, the Redis hold, the database writes — already lives in `ReservationService`, built across
earlier tasks. This class's whole job is translation: turning "a message arrived" into "here is a
booking decision to make," and nothing more.

## Why no transaction annotation lives here, even though the work behind it is entirely transactional

It would seem natural to mark this method `@Transactional`, since everything it triggers needs to be
atomic. The reason it isn't: `ReservationService` already opens its own transaction (and, if two
requests collide, opens a second one to retry in) around exactly the work that needs to be atomic.
Wrapping ANOTHER transaction around this listener method would also wrap Kafka's own bookkeeping for
this delivery — deciding whether the delivery succeeded, tracking how many times it's been retried —
inside that same boundary, tangling two things that need to stay separate: the fact of receiving a
message, and the transactional work that message triggers.

## Why nothing in this class ever "acknowledges" a message by hand

Kafka's own rule is simple: it only considers a message delivered once the method handling it returns
normally, without throwing. Throwing is the ENTIRE signal for "this didn't work, please try again" —
there's no separate step to manually confirm success or failure. If this class checked the schema
version and it didn't match, throwing `UnknownSchemaVersionException` is not just an error report, it's
the literal mechanism that tells Kafka to redeliver (or, since that exception is marked non-retryable
elsewhere, to dead-letter immediately instead). Writing any kind of manual acknowledgement here would
create a second place the "was this actually handled" answer could get out of sync with what really
happened.

## The necessary companion change to `ReservationService`

`ReservationService.decide(orderId, showId, seatIds)` — the three-argument version every User Story 1
and User Story 2 test already calls directly — has no idea a message id exists, on purpose: those
tests exercise the decision logic itself, always exactly once, and threading a message identity through
every one of them would ask each test to invent an identity that means nothing to what it's actually
testing. This listener needed a genuine fourth entry point instead: a `decide(messageId, orderId,
showId, seatIds)` overload that checks `IdempotencyGuard.isFirstDelivery(messageId)` as the very FIRST
thing it does, inside the SAME transaction as everything that follows — exactly what
contracts/inventory-consumer.md's own step-by-step ordering requires. This overload is the only thing
this listener ever calls in production.

## Verifying it

`SagaEndToEndIT` (T171) is the test built specifically to prove this class does its one job correctly:
publish a real `OrderCreated` the way order-service really would, and confirm a real `seats.reserved`
comes back out. That test still fails today — not because this class is wrong, but because the
idempotency guard it calls into (`IdempotencyGuard`, T174) is still a stub awaiting the developer
exercise. `UndecidableRequestIT`'s own `unknownVersionGoesToDlt` and `dlttedAtAttemptLimit`, which don't
depend on the guard at all, both pass — direct evidence this class's schema check and its wiring into
the retry/dead-letter machinery both work correctly against a real broker.
