# T086 — `CapacityMetrics`, so overload shows up on a dashboard

**What this task did:** wrote the small class that turns every 503 refusal into a Prometheus counter
— the last piece FR-036 asks for: overload must be *visible*, not just correctly reported to the one
caller who happened to hit it.

```java
@Component
public class CapacityMetrics {
    private final Counter capacityRefusals;

    public CapacityMetrics(MeterRegistry meterRegistry) {
        this.capacityRefusals = Counter.builder("orders.refused.capacity")
                .description("Booking requests refused because the service was at capacity")
                .register(meterRegistry);
    }

    public void recordCapacityRefusal() {
        capacityRefusals.increment();
    }
}
```

---

## The difference between a log line and a metric

`ApiExceptionHandler` (T084) could have logged "refused: at capacity" every time it returns a 503.
That would be true, and nearly useless for the question that actually matters operationally: *is this
happening a lot, right now, across every instance of this service?* A log line answers "did it
happen, to this one request, on this one machine." A **counter** — `orders.refused.capacity` here —
accumulates across every request and every instance, and Prometheus can be asked "how fast is this
number rising?" A rising rate is the difference between "one unlucky request" and "the service is
genuinely out of capacity," and only the counter can answer that.

## Why the counter has its own class instead of living inline in the exception handler

```java
Counter.builder("orders.refused.capacity").register(meterRegistry)
```

Micrometer's `MeterRegistry` does not check whether a meter name already exists with different
intent before creating one — ask for `"orders.refused.capacit"` (one letter short) somewhere else in
the codebase, and Micrometer happily creates a second, differently-named meter, with nothing to say
you probably meant the first one. Giving the counter's construction exactly one home is what makes
the name a single point of truth: every place in the codebase that wants to record this refusal calls
`recordCapacityRefusal()` on this one instance, and the string `"orders.refused.capacity"` is written
down exactly once, here, rather than risked at every call site.

## Where it will surface

Once `/actuator/prometheus` is scraped (verified for real in T106, the Polish phase), this counter
appears as a standard Prometheus counter metric, ready for a dashboard panel or an alert rule —
"page someone if `rate(orders_refused_capacity_total[5m]) > 0` for more than a minute," say. That
kind of alert is what turns "a buyer complained the app was slow once" into "we knew it was happening
before anyone had to complain."

---

## Confirmed

`OrderCapacityIT` passing (T084's commit) already exercises this class indirectly — its 503 response
only happens because `ApiExceptionHandler` calls `capacityMetrics.recordCapacityRefusal()` on the way
to building it. A direct check that `/actuator/prometheus` exposes the metric by name is T106's job,
once the other four meters from `OutboxMetrics` (Phase 4) exist alongside it and all five can be
verified together.
