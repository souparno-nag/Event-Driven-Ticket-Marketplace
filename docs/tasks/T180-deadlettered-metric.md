# T180 — `inventory.messages.deadlettered`

**What this did:** added the fifth and final meter research.md's own metrics decision names — a
count of messages this service gave up on entirely, incremented at the one place that actually knows a
message has reached that point.

---

## Why a service that decides nothing needs its own, separate counter

Picture a graph showing only two numbers: how many bookings were granted, and how many were refused.
Now picture this service's consumer completely broken — every message failing before a decision is
even attempted. Both of those two numbers would sit at zero, and a zero-and-zero graph looks EXACTLY
like a service that simply isn't receiving any traffic at all. There would be nothing on the dashboard
distinguishing "nothing to do" from "something is badly wrong and nothing is getting through." This
counter is what makes that distinction visible: a rising `inventory.messages.deadlettered` count, next
to two flat lines at zero, is unambiguously "messages are arriving and this service cannot handle
them," not silence.

## Why this counter is incremented from the Kafka configuration, not from the decision logic

Every other meter this service defines gets incremented from inside `ReservationService`, because that
class is where every decision actually happens. A message that ends up dead-lettered, by definition,
never reached a decision at all — either its shape was never recognised, or every attempt to process
it failed before finishing. There is no method inside the decision logic to call this counter from,
because the decision logic was never reached. The one place that genuinely knows a message has reached
its bounded end — after every retry, right as it's about to be moved aside for good — is the Kafka
error-handling configuration itself, so that's where this counter is incremented.

## Verifying it

No dedicated test, matching this service's own established precedent for metrics classes
(`DecisionMetrics`, T166) — incrementing a counter isn't complex enough logic to earn a test of its
own. Its wiring is exercised for free by `UndecidableRequestIT`'s `dlttedAtAttemptLimit` and
`unknownVersionGoesToDlt`, both of which genuinely drive a message all the way to the dead-letter
channel; if this counter's wiring were broken, the error handler itself would fail to construct and
every test in the module would fail immediately, not silently.
