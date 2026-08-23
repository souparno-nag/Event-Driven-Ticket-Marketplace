# T070 — JSON configuration

**What this task did:** configured how this service converts objects to JSON and back, and fixed
one default that would otherwise produce messages a consumer rejects.

---

## Why each service configures this separately

The `common-events` module defines the message shapes, but it deliberately ships **only Jackson's
annotations** — no serializer. That was a decision in build step 1: the contract module describes
what a message looks like without dictating how each service writes it, so no service is forced to
accept another's JSON settings.

The cost of that decision is this file. Each service configures serialization itself, and each
service can therefore get it wrong on its own.

---

## The setting that matters

```java
.enable(SerializationFeature.WRITE_BIGDECIMAL_AS_PLAIN)
```

Money is stored as `BigDecimal`. Left alone, Jackson may write one in **scientific notation**:

```json
{"amount": 1E+2}      instead of      {"amount": 100.00}
```

Both parse back to the same number, so nothing in this service would ever notice. The failure lands
in a **different service** — one whose schema expects a plain decimal — and by then the message is
already on the channel and the order is already accepted.

That is the shape of this whole class of bug: a default that is perfectly fine until the value
crosses a service boundary. Which is why it is set deliberately rather than assumed.

## Building on Boot's mapper, not replacing it

```java
@Bean
public ObjectMapper objectMapper(Jackson2ObjectMapperBuilder builder) {
    return builder.build()
        .enable(...)
```

The obvious way to write this is `new ObjectMapper()` and configure it. That would be a mistake.

Spring Boot's builder arrives pre-loaded with every Jackson module it found on the classpath —
including `JavaTimeModule`, which teaches Jackson about `Instant`. Starting from a bare mapper drops
all of it, and the first symptom is an `Instant` serializing as a nested object of internal fields:

```json
"occurredAt": {"seconds": 1787482901, "nanos": 230000000}
```

That reads like a bug in the message record. It is not; it is a missing module. Building from the
builder keeps Boot's setup and layers three explicit decisions on top.

Declaring an `ObjectMapper` bean makes Boot's own back off, so there is exactly one mapper in the
application — used both for HTTP bodies and for the outbox payload. Two differently configured
mappers in one service is a bug waiting for the day an amount looks right in an API response and
wrong on the wire.

## The other two settings

**`WRITE_DATES_AS_TIMESTAMPS` disabled** — writes an `Instant` as `"2026-08-23T09:15:30.123456789Z"`
rather than as a number. Boot already disables it; it is repeated because the wire format of a frozen
contract should not depend on a framework default that a future version could reasonably change.

**`FAIL_ON_UNKNOWN_PROPERTIES` disabled** — an unrecognised field is ignored rather than throwing.
During a rolling deployment, an older consumer meets messages from a newer producer. A producer
adding a field is supposed to be a safe, backward-compatible change; failing on unknown fields is
exactly what would make it a breaking one.

---

## A caution recorded in the file

`Instant` survives a JSON round trip at **nanosecond** precision. PostgreSQL's `timestamptz` stores
**microseconds**.

So a timestamp that has been through the database is not equal to the one that went in — it has been
quietly truncated. Nothing warns you. A test comparing a stored time to the original fails with a
difference of a few hundred nanoseconds, which looks like anything except rounding. Comparisons
involving stored times need to truncate to microseconds first.

---

## Why code rather than three lines of YAML

All three settings exist as `spring.jackson.*` properties and could have gone in `application.yml`
instead.

They are code because each is a **correctness requirement of the message contracts**, not an
environment setting — and `application.yml` is the file people edit when moving between
environments. A value that must never differ between environments does not belong in the file whose
purpose is to differ between them.
