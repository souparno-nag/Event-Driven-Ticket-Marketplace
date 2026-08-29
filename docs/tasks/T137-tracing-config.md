# T137 — `TracingConfig`, ported

**What this task did:** copied order-service's `TracingConfig` into this service unchanged — same one
bean, same reasoning, same javadoc explaining a Spring Boot autoconfiguration quirk that applies here
for the identical reason it applies there. This closes out Phase 2's Configuration section and, with
it, the whole batch of tasks needed before anything in this service can actually consume a message.

---

## Why "unchanged" is correct here, and not just convenient

Every other ported config class in this batch had at least one paragraph that needed rewriting because
it described a job specific to order-service — `JacksonConfig`'s HTTP-bodies mention, most notably.
`TracingConfig` doesn't, and that's worth noticing rather than skipping past: the failure it works
around isn't a property of what either service *does*. It's a property of exactly which library
versions sit on the classpath together.

Order-service's own Javadoc names the failure precisely: with `micrometer-tracing-bridge-brave` present
and no explicit `Propagation.Factory` bean, Spring Boot's own `BravePropagationConfigurations` has
three competing autoconfiguration branches for providing one, and every combination of
`management.tracing.*` properties still left the no-op `NoPropagation` branch winning — confirmed by
direct inspection, not assumed from documentation: `propagator.fields()` came back empty, and
`propagator.inject(...)` silently wrote nothing anywhere, no exception, no warning. This service's
`pom.xml` (T115) carries the exact same three dependencies in the exact same versions — Boot 3.3.13,
`micrometer-tracing-bridge-brave`, `brave-propagation-tracecontext` — that produce that failure. Same
inputs, same broken output, so the same one-line fix applies without needing to be re-derived.

## What this class actually fixes, restated for what depends on it here

Declaring `Propagation.Factory` directly, rather than asking Boot's autoconfiguration to assemble one,
means the same `@ConditionalOnMissingBean(Propagation.Factory)` that let the no-op win before now finds
*this* bean first — every one of Boot's three competing branches backs off, and downstream
autoconfiguration builds a real, working `Propagator` bean from it automatically.

That `Propagator` bean is what `OutboxRelay.attachTraceHeaders()` (T133) actually calls —
`propagator.extract(...)` to reconstruct a stored trace, `propagator.inject(...)` to write a new span's
context onto an outgoing Kafka message's headers. Before this task existed, any test wanting to
exercise that code path had no working `Propagator` to hand it and had to substitute a mock instead,
recorded honestly in T133's own write-up. This is the piece that makes the real thing available.

It's also literally half of SC-015's own success criterion. That criterion asks for *one* connected
trace spanning order-service's acceptance, its own outbox publish, this service's decision, and this
service's own outbox publish. Order-service's copy of this exact fix is what lets a trace survive its
first outbox gap; this copy is what lets that same trace survive the second one, here.

---

## Verifying it

Verified as part of the combined smoke test described in T134–T136's write-ups, and this is the
specific claim that matters most: **a real, working `Propagator` bean exists in the application
context with zero test-side substitution.** The previous batch of tasks (T130–T133) needed a mocked
`Propagator` to boot at all, because nothing yet supplied a real one. With this class in place, the
identical assertion research.md's own failure report used to detect the bug —
`propagator.fields()` — was checked directly against the live bean and came back non-empty, the
opposite of the broken behavior this class exists to prevent. No mock, no stub, the genuine article,
autowired successfully into a test that asked for it by type alone.

---

## Phase 2's Configuration section is now complete

`JacksonConfig`, `RedisConfig`, `KafkaProducerConfig`, and `TracingConfig` — T134 through T137 — are
all in place. Combined with Phase 2's domain layer (T123–T129) and its ported outbox (T130–T133), this
service now has every table, every entity, a fully working second outbox, and every piece of
infrastructure configuration a real message-driven decision needs to run on. What remains before Phase
2 itself is finished is the shared test infrastructure (T138–T141) — after that, Phase 3 can begin
building the part of this service that actually decides anything.
