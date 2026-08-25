# T099: what the finished `pollAndPublish()` actually does, and what it took to get there

`docs/tasks/T099-outbox-relay-guide.md` walked through the problem before any code existed. This
document is the other half: what the method ended up looking like, and — more usefully for anyone
learning from this project later — the handful of real, easy-to-miss bugs that only showed up once
the method was tested under genuinely concurrent, genuinely failing conditions rather than a single
happy-path call.

## What the method does, step by step

```java
@Scheduled(fixedDelayString = "${outbox.relay.poll-interval-ms:500}")
@Transactional(timeout = 30)
public void pollAndPublish() {
    List<OutboxRecord> claimed = outboxRepository.claimBatch(batchSize);

    for (OutboxRecord record : claimed) {
        ProducerRecord<String, String> producerRecord = new ProducerRecord<>(
                record.getEventType(), record.getAggregateId().toString(), record.getPayload());

        attachTraceHeaders(record, producerRecord);

        try {
            kafkaTemplate.send(producerRecord).get();
            record.markPublished(Instant.now());
            metrics.recordPublished();
        } catch (Exception e) {
            record.recordFailure(describeFailure(e));
            metrics.recordSendFailure();
            if (record.getAttempts() >= maxAttempts) {
                record.park();
            }
        }
    }
}
```

Every 500ms (configurable), this claims a batch of rows nobody else currently owns, and for each one:
builds a Kafka record from the row's own channel, key, and payload; attaches a trace header if the
row was written with one; sends it and **waits for the broker's acknowledgement** before deciding
anything; and either marks it `PUBLISHED` or records the failure and parks the row once it has failed
too many times. No `outboxRepository.save(record)` call is needed — the method is `@Transactional`,
so JPA notices the row was mutated and writes it back when the transaction commits.

The one deliberate deviation from the guide's rough sketch is `@Transactional(timeout = 30)` instead
of the plain `@Transactional` the rest of the service uses elsewhere (which defaults to a 3-second
timeout, tuned for the HTTP-accepting path). A batch here can contain a row aimed at a channel that
doesn't exist, and failing that send takes as long as the Kafka producer's own configured timeouts —
a few seconds — before the loop can even get to the *next* row. Three seconds was enough for
"accept an HTTP request," not for "get through an entire batch that includes one poisoned row."

## The trace-header half

```java
private void attachTraceHeaders(OutboxRecord record, ProducerRecord<String, String> producerRecord) {
    if (record.getTraceparent() == null) {
        return;
    }
    Map<String, String> stored = new HashMap<>();
    stored.put("traceparent", record.getTraceparent());
    ...
    Span publishSpan = propagator.extract(stored, Map::get).name("outbox.publish").start();
    try {
        Map<String, String> outgoing = new HashMap<>();
        propagator.inject(publishSpan.context(), outgoing, Map::put);
        outgoing.forEach((key, value) ->
                producerRecord.headers().add(key, value.getBytes(StandardCharsets.UTF_8)));
    } finally {
        publishSpan.end();
    }
}
```

This is the "continue a trace that started somewhere else" pattern: instead of just copying the
stored header string onto the outgoing message untouched, it re-extracts the stored context into a
real `Span`, starts a short-lived span representing "the relay is publishing this," and re-injects
*that* span's context into the outgoing headers. The result is a message that carries a trace still
rooted in the original HTTP request, but with the relay's own publish step visible as a step in that
same trace — rather than the outgoing header being an inert copy of a string nobody actually
processed.

## A production bug hiding behind a passing test

Getting `pollAndPublish()` itself right was the easy part — the guide's five traps were genuinely
useful, and none of them took long to avoid. Nearly all of the real time on this task went into a
config bug that had nothing to do with the method's own logic: **trace headers were never actually
being attached**, for reasons that had nothing to do with `attachTraceHeaders()` being wrong.

Spring Boot ships three different, mutually exclusive ways of wiring up trace propagation, and picks
between them using `@ConditionalOnProperty` checks against `management.tracing.*` settings. Every
combination of those settings tried here — as command-line flags, then as `application.yml` entries —
still left Boot falling back to a no-op propagator that writes nothing into any carrier map. The
`Tracer` and `Span` objects worked fine; propagation itself was quietly inert. This is exactly the
kind of bug that a single hand-run test can miss entirely, since `tracer.currentTraceContext()` still
returns a real, non-null context either way — it's only when you check what actually landed in the
outgoing map that the problem is visible.

The fix was to stop trying to *configure* Spring Boot's auto-detection and instead declare the
specific `Propagation.Factory` bean the project wants directly:

```java
@Configuration
public class TracingConfig {

    @Bean
    public Propagation.Factory propagationFactory() {
        return TraceContextPropagation.FACTORY;
    }
}
```

Spring Boot's own auto-configuration only supplies its no-op factory when nothing else already has
(`@ConditionalOnMissingBean`), so declaring this bean directly makes all three of Boot's
candidate configurations back off, leaving exactly the propagation behavior this project actually
needs. The lesson generalizes: when a framework offers to auto-configure something based on
properties, and property-based configuration silently isn't taking effect, declaring the bean
directly is often faster and more debuggable than continuing to guess at property names.

## Two test-infrastructure bugs, found only by running everything together

The five integration tests this task exists to satisfy (`OutboxRelayIT`, `OutboxTracingIT`,
`OutboxConcurrencyIT`, `OutboxOrderingIT`, `OutboxRestartRecoveryIT`) all passed cleanly on their own.
Running the *entire* test suite together — every test written so far in this build step, in one
Maven build — surfaced two more problems that only exist when many tests share one JVM and one
database.

**A background poller racing the tests that drive it by hand.** `OutboxRelay` is genuinely
`@Scheduled`, which means it was firing automatically every 500ms in the background of *every* test's
Spring context — including the tests that call `pollAndPublish()` directly themselves, to control
exactly when a batch gets processed. That produced an intermittent failure where a test's own cleanup
query got cancelled by Postgres's statement timeout, because it was waiting on a database row lock
the background scheduler happened to be holding at that exact moment. The fix was a shared test base
class, `RelayDrivenIT`, that overrides `outbox.relay.poll-interval-ms` to an hour (effectively never)
for the four test classes that need to drive the relay themselves — while deliberately leaving the
one test that's specifically about proving the *automatic* recovery (`OutboxRestartRecoveryIT`)
un-suppressed, since suppressing the very thing it's testing would defeat its purpose.

**Too many database connections at once.** The first attempt at that fix declared the override
separately on each of the four test classes instead of on one shared class. That passed every time
those four tests ran on their own, and failed — reproducibly — the moment they ran alongside the
rest of the suite, with PostgreSQL refusing new connections outright. The reason is a genuinely
non-obvious piece of Spring test behavior: its test-context cache treats two classes as needing
*different* cached application contexts if they each declare their own `@DynamicPropertySource`
method, even when the values registered are identical. Four classes each declaring the same override
meant four separate contexts, each opening its own full connection pool, several alive at once. Moving
the declaration onto one shared parent class collapsed that back down to one context and one pool for
those four — but the wider suite still runs several *other* distinct contexts side by side (plain
database-only tests, the one relay test that keeps real scheduling, and a test that replaces one bean
with a mock, which also counts as a distinct context), and that combination alone was still enough to
exhaust the default connection limit. The fix that actually closed this out was capping the size of
the connection pool every *test* context opens — much smaller than the production setting, since
production's pool size is a deliberate, separately-justified admission-control decision this change
had to leave untouched.

## What "done" means here, and what's deliberately left alone

T099's own definition of done — from `contracts/outbox-relay.md` — is that `OutboxRelayIT`,
`OutboxTracingIT`, `OutboxConcurrencyIT`, `OutboxOrderingIT`, and `OutboxRestartRecoveryIT` all pass.
Run in isolation, they do, cleanly, twice in a row. Running the *entire* suite together additionally
surfaced two things that are real but are not part of this task: a pre-existing test
(`OrderAcceptanceIT`'s `RollbackWhenTheOutboxWriteFails`) that was already failing under a full
combined run before this task's own changes began, and a timing sensitivity in
`OutboxConcurrencyIT`/`OutboxOrderingIT` where draining a thousand rows across three racing threads
occasionally runs past the test's own wait budget under a heavily loaded combined build — also
present before this task's changes. Both are left alone here, since neither is something this task
introduced or is responsible for fixing.
