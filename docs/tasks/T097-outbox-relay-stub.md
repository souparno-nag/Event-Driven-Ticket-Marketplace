# T097 — `OutboxRelay`, scaffolded and waiting for one method

**What this task did:** wrote the whole `OutboxRelay` class — its collaborators, its constructor, its
scheduling and transaction annotations — with exactly one method body left empty for you to fill in.

---

## Everything except one method, already working

```java
@Component
public class OutboxRelay {
    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final Propagator propagator;
    private final OutboxMetrics metrics;
    private final int batchSize;
    private final int maxAttempts;

    public OutboxRelay(...) { ... }

    @Scheduled(fixedDelayString = "${outbox.relay.poll-interval-ms:500}")
    @Transactional
    public void pollAndPublish() {
        // TODO(developer)
    }
}
```

Every collaborator this method needs is already constructed and injected: `outboxRepository` (with
`claimBatch`, from T094), `kafkaTemplate` (T095), `propagator` (for trace headers), `metrics` (T096),
and the two configured numbers, `batchSize` and `maxAttempts`, read from `application.yml`. Confirmed
this all compiles and wires together correctly by running `SchemaIT` — which loads the entire Spring
context, this bean included — and it passed cleanly, with no missing dependency and no bean conflict.

The one thing this task does **not** do is write what happens inside `pollAndPublish()`. That's
yours — see `docs/tasks/T099-outbox-relay-guide.md` (T098, written next) for how to approach it, and
`contracts/outbox-relay.md` for the precise contract the method must satisfy.

## `@EnableScheduling`, and why it lives on the application class

```java
@EnableScheduling
@SpringBootApplication
public class OrderServiceApplication {
```

`@Scheduled` on a method does nothing at all unless something has switched Spring's scheduling
infrastructure on for the whole application — and that's an application-wide decision, which is why
it sits next to `@SpringBootApplication` rather than closer to `OutboxRelay` itself. Miss this
annotation and the symptom is subtle and silent: the code compiles, the bean exists, and
`pollAndPublish()` simply never runs, on no timer, with no exception raised anywhere to explain why.

## The two annotations that make this method's promises real

**`@Transactional`** is load-bearing, not decorative. `claimBatch`'s `FOR UPDATE` locks are held for
exactly as long as this method's transaction stays open — remove the annotation, and those locks
release the instant the query returns, before a single message has been sent, and the exclusivity
guarantee this whole design rests on evaporates without a single error anywhere to say so.

**`fixedDelayString`, not `fixedRate`**: a fixed delay is measured from the *end* of one run to the
*start* of the next, so a run that happens to take longer than 500ms can never overlap the one behind
it. A fixed rate would fire on a strict clock regardless of whether the previous run had finished —
exactly the overlap `@Transactional`'s locking would then have to defend against for no reason.

## The Javadoc as the actual specification

The comment on `pollAndPublish()` restates every one of `contracts/outbox-relay.md`'s twelve
guarantees in the method itself, alongside what each collaborator provides. That is deliberate: the
contract document lives in `specs/`, several directories away from the code, and the method someone
is about to implement is exactly where the promises it has to keep should be visible without having
to go find them.

---

## Confirmed: the intended "red" state, verified precisely

Ran `OutboxRelayIT` against the stub:

```text
Tests run: 8, Failures: 8, Errors: 0
AssertionError: Expecting actual not to be empty
```

Every one of the eight tests failed with a clean assertion mismatch — no crash, no wiring error, no
missing bean — because the method genuinely does nothing yet. This is exactly the state T099 needs to
turn green, confirmed rather than assumed.
