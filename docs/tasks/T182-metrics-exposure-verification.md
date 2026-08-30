# T182 — verifying `/actuator/prometheus` exposes all five R13 meters

**What this did:** wrote and ran the test proving research.md R13's complete list of five meters is
genuinely reachable the way Prometheus itself reaches it — a real HTTP scrape of a real endpoint, not
an inspection of objects already sitting in memory — and found a real, worth-explaining gap along the
way: the endpoint didn't exist at all until a specific property was set.

---

## Why "the code registers the meter" and "Prometheus can actually read it" are two different claims

Every meter in `DecisionMetrics` and `OutboxMetrics` gets built and registered in the application's own
`MeterRegistry` object the moment those classes are constructed — that part was already true from
earlier tasks, and easy to confirm by just reading the code. What has never actually been checked until
this task is the SECOND half of the claim FR-045 makes: that a real HTTP request to
`/actuator/prometheus` returns those meters in Prometheus's own text format. Those are genuinely
different things — a registry full of meters with nothing serving them over HTTP is invisible to the
monitoring system this whole feature exists to feed.

## A real bug this test's first run caught, and the wrong guesses tried before the right fix

The very first run of this test found `/actuator/prometheus` returning a plain 404 — the endpoint
simply didn't exist. Two increasingly informed guesses were tried and both turned out to be wrong,
worth recording so the same guesses aren't tried again:

1. **First guess**: add `management.defaults.metrics.export.enabled: true` to `application.yml`.
   Reasonable — that's genuinely the property Spring Boot's own condition-evaluation report named as
   the reason the endpoint's autoconfiguration didn't match. Checking the property back from the live
   application afterward showed it was STILL resolving to `false`, despite the file plainly saying
   `true`.
2. **Second guess**: override the same property with `@TestPropertySource` directly on the test class,
   assuming the file's own value was simply losing to some other configuration. Also silently
   overridden — confirmed the same way, by reading the property back rather than trusting the fix
   looked right.

The actual cause, found by directly asking the running application what value it saw rather than
guessing again: Spring Boot's own `@SpringBootTest` support disables metrics export by default for
every test — a sensible convention on its own, meant to stop an ordinary test from accidentally trying
to publish real metrics to a real monitoring system — and it does so at a property precedence level
ABOVE both a properties file and `@TestPropertySource`. The only mechanism that actually wins is
`@DynamicPropertySource`, which Spring evaluates later than either of those. Switching to it fixed the
test immediately, confirmed by reading the same property back a third time and finally seeing `true`.

## Why the test decides one request per cause before scraping, rather than checking a fresh application

`inventory.holds.refused` is a tagged counter, and Micrometer only creates one specific tag combination
the moment something is actually recorded against it — not in advance, and not for every combination
that could ever exist. A completely fresh application, having decided nothing yet, would not expose
this meter AT ALL, tags included. A test that scraped `/actuator/prometheus` without first triggering
one refusal of each kind would still pass, but it would be checking that the endpoint exists, not that
the specific claim FR-045 makes — a `cause` tag for each of the three causes — is actually true. This
test drives one decision through each of the four possible outcomes first, specifically so the scrape
afterward is checking something that was genuinely just produced.

## Verifying it

```text
Tests run: 1, Failures: 0, Errors: 0
```

All five meters confirmed present in a real scrape: `inventory_holds_granted_total`,
`inventory_decision_duration_seconds` (a timer, exposed as several related series sharing that
prefix), `inventory_messages_deadlettered_total`, `inventory_outbox_oldest_pending_age`, and
`inventory_holds_refused_total` carrying all three `cause` tag values.
