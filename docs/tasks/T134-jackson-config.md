# T134 — `JacksonConfig`, ported

**What this task did:** copied order-service's `JacksonConfig` into this service, keeping all three
settings unchanged, and rewrote the one paragraph of its Javadoc that described a job this service
doesn't have.

---

## What genuinely ported unchanged

All three settings — `WRITE_BIGDECIMAL_AS_PLAIN` enabled, `WRITE_DATES_AS_TIMESTAMPS` disabled,
`FAIL_ON_UNKNOWN_PROPERTIES` disabled — are correctness requirements of the *message contracts*
themselves, not of anything specific to order-service. A `BigDecimal` written in scientific notation is
just as wrong on a message this service produces as on one order-service produces; an `Instant`
serialized as a nested object of internal fields breaks identically either way. None of the reasoning
needed to change, so none of the code did either.

## The one paragraph that had to be rewritten, and why

Order-service's own class doc says: "The same mapper serves both jobs on purpose: HTTP request and
response bodies, and the outbox payload." That sentence is simply false if copied verbatim into this
service — `inventory-service` exposes no HTTP API beyond actuator health and metrics endpoints
(`contracts/README.md` is explicit about this: availability is the read model's job, arriving in step
6). Copying a comment that describes a job this code doesn't do would be a small but real form of the
exact failure mode WHY comments exist to prevent — a reader trusting the comment over the code, and
being wrong.

This service's actual two jobs for its one shared `ObjectMapper` are different: **deserializing** the
consumed `OrderCreated` message (`KafkaConsumerConfig`, arriving in a later task) and **serializing**
the `SeatsReserved`/`SeatsRejected` outbox payload (`OutboxWriter`, also later). The underlying point —
one configured mapper for every JSON job in the service, so a value can't look right going one direction
and wrong going the other — survives the rewrite exactly. It's just true about a different pair of
directions here: in and out of the *message channel*, rather than in and out of an *HTTP* endpoint.

## Verifying it

Verified as part of a combined smoke test with T135–T137 (see T137's write-up for the full context this
test needed and why): a real, fully wired `ObjectMapper` correctly wrote a `BigDecimal("100.00")` as
`100.00` rather than `1E+2`, correctly wrote an `Instant` as an ISO-8601 string rather than a raw
number, and correctly deserialized a JSON payload carrying an extra, unrecognized field without
throwing — all three settings proven to actually take effect against a live `ObjectMapper` bean, not
merely asserted to be present in the builder chain.
