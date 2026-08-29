# T136 — `KafkaProducerConfig`, ported

**What this task did:** copied order-service's `KafkaProducerConfig` into this service unchanged in
every setting. This is the producer `OutboxRelay` (T133) has been waiting for since it was ported —
until this task, the relay could only be *tested* against Spring Boot's own generic default template,
or a mock. Now it has the real thing.

---

## Why every setting here ported without a single change

Read through what this class actually configures: `acks=all`, an idempotent producer, a five-in-flight
cap, shortened `max.block.ms`/`request.timeout.ms`/`delivery.timeout.ms`, and `StringSerializer` for
both key and value. Not one of those is a decision about *what* gets published — they're all decisions
about what "publish safely" means for any outbox row, in any service, carrying any message. `acks=all`
means the same thing whether the row says `order.created` or `seats.reserved`: don't call it delivered
until every in-sync replica has it. The producer doesn't know or care what's in the payload it's
handed — literally, since it's typed `KafkaTemplate<String, String>` and treats every payload as an
opaque already-serialized string, never touching the object that produced it.

That last point is worth sitting with for a second, because it's the whole reason `StringSerializer`
is the *correct* choice here and not merely the convenient one. The message was already turned into
JSON once, when its `OutboxRecord` was written (`OutboxWriter`, a later task) — that's the entire
purpose of storing the payload as a `String` column instead of a live Java object. Serializing again
here — turning a `String` back into some intermediate representation just to immediately turn it back
into bytes — is exactly the kind of round trip where a bug like `WRITE_BIGDECIMAL_AS_PLAIN` was written
to prevent (`JacksonConfig`, T134) could quietly reappear a second time, for no reason. Using
`KafkaTemplate<String, String>` doesn't just avoid that risk, it makes the producer *incapable* of
reintroducing it — there's no second serialization step for a bug to hide in.

The one thing worth double-checking on a port like this, rather than assuming: does building the
producer from `KafkaProperties.buildProducerProperties(null)` still correctly pick up wherever
`spring.kafka.bootstrap-servers` is actually configured for *this* service, rather than order-service's?
Yes — `KafkaProperties` is a Spring Boot autoconfiguration bean scoped per application context, so each
service's own instance reads its own `application.yml`. Nothing about this being a second service
changes how that resolution works.

---

## What this unblocks

Before this task, a test exercising `OutboxRelay` had exactly two options: accept whichever
`KafkaTemplate` Spring Boot happened to auto-configure by default (untyped, uncontrolled acks/retry
behavior), or stand up a mock and hope it actually gets wired in ahead of Boot's own bean — which,
recorded honestly in T133's own write-up, didn't reliably work. Now there's a third, correct option:
the relay is wired to a `KafkaTemplate<String, String>` this service actually controls the safety
properties of, the same way order-service's relay always has been.

---

## Verifying it

Verified as part of the combined smoke test described in T134, T135, and T137's write-ups. The
significant, concrete result: with this class now in place, the same full-context boot that had
previously (during T130–T133's own verification) triggered Spring Boot's own default producer as a
side effect no longer does — the autowired `KafkaTemplate<String, String>` this service's own
`OutboxRelay` receives is now unambiguously *this* class's bean, confirmed by the absence of the
duplicate producer construction that showed up in logs before this task existed.
