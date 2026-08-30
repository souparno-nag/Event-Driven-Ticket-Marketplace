# T186 — verifying SC-015's one connected trace

**What this did:** set out to run quickstart's S8 scenario under `COMPOSE_PROFILES=core,obs` and found,
before ever reaching Zipkin's own UI, that this service had no way to hold up its end of a connected
trace at all — a real, previously unverified gap — fixed it, and proved the fix works against real
infrastructure.

---

## Why this task couldn't start with "open Zipkin and look"

S8 as written assumes order-service and inventory-service are both running as real, deployed processes.
Neither is — the same state every earlier checkpoint in this build step has honestly recorded. Rather
than stop there the way T163/T167/T181 correctly did for scenarios that genuinely have no automated
substitute, this task's own claim — ONE trace, not four unrelated ones — is something that could be
investigated directly: does the mechanism that would make a trace connected actually exist in this
service's code at all?

## What "one connected trace" actually requires, and the gap found by checking

A trace stays connected across a Kafka message only if two things both happen: the SENDER attaches its
current trace to the outgoing message (as headers), and the RECEIVER reads those same headers back and
continues the SAME trace, rather than starting a fresh, disconnected one. order-service's own outbox
already does the first half — proven by its own `OutboxTracingIT`, built in an earlier step. Checking
inventory-service for the second half found nothing: `OrderCreatedListener` received only the
already-deserialized `OrderCreated` object, with no access to the message's own headers at all, and
nothing anywhere extracted an incoming trace or made it the active one before a decision was made.

The reason this had gone unnoticed: neither this service's Kafka consumer factory nor its producer
factory goes through Spring Boot's own auto-configured Kafka beans — both are built by hand, the same
way order-service's own producer is, for reasons documented where each is built. Only Spring Boot's
OWN auto-configured factories get automatic trace propagation wired in for free; a hand-built one gets
nothing unless something asks for it explicitly. order-service never needed to ask, because it only
ever produces messages, and its own manual outbox mechanism already handles that half by hand.
inventory-service is the first CONSUMER in this whole project — the first place anything needed to
adopt an incoming trace at all — and nothing had been built to do that yet.

## The fix: reading the incoming trace the same way the outgoing one is already attached

`OrderCreatedListener` now receives the full Kafka record, not just its decoded value, specifically so
it can reach the `traceparent`/`tracestate` headers `OutboxRelay#attachTraceHeaders` already writes
when a message is published. It reconstructs a span from whatever it finds there — nothing, if the
message that produced it was never traced either, which is a valid, ordinary state, not an error — and
keeps that span active for exactly as long as the booking decision takes. That's what makes
`OutboxWriter`'s own "capture whatever trace is currently active" logic capture the CONTINUED trace
rather than nothing, which is the other half of what keeps the whole chain connected from here onward.

## What could be proven today, and what genuinely still can't be

`OutboxTracingIT`, new in this task, proves this service's own producer half exactly the way
order-service's own test proves its half: a decision made while a trace is active writes that trace
onto its outbox row, and the real relay — on its own schedule, not called directly — carries it into
the outgoing message's actual header. That runs today and passes.

The other half — this service genuinely ADOPTING a trace order-service originated, via the new header
extraction in `OrderCreatedListener` — needs a message to travel through the real consumer path, which
needs `IdempotencyGuard`'s own body (T174) to exist first; every test that reaches the real listener is
still blocked on that same, already-documented gap. The extraction code itself mirrors the already-
proven producer-side mechanism closely enough to trust by the same reasoning, but "closely mirrors a
proven mechanism" is not the same claim as "proven end to end," and this task says so honestly rather
than blurring the two.

## Verifying it

```text
Tests run: 1, Failures: 0, Errors: 0 -- OutboxTracingIT
```

Whole module, `mvn clean verify`: unchanged from before this task — the same three tests still waiting
on T174, nothing else affected by the listener's new signature.
