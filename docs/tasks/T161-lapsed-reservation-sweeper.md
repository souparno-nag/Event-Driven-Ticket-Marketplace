# T161 — `LapsedReservationSweeper`, and a scheduling gap it exposed

**What this task did:** wrote `LapsedReservationSweeper`, the background job that retires lapsed
reservations nobody has contended for again (FR-019). Verifying it actually fires on its own timer —
not just that its method body is correct — surfaced a real gap left over from T130–T133: this service
never actually enabled Spring's scheduling infrastructure at all.

---

## Tidy-up, restated concretely rather than left as an assertion

`ReservationService.decide(...)` already retires a lapsed reservation inline, the instant anything next
contends for its seats, in the same transaction as the new booking (FR-018, T160). This class exists
for the case that inline path never covers: a hold on a show nobody ever rebooks. Without a sweeper,
that reservation sits `HELD`, with a lapse time in the past, forever — looking live to anything that
doesn't know to compare it against the clock. `LapsedRebookingIT` (T149) is the test that proves this
class is genuinely optional for correctness: it disables the sweeper entirely and shows rebooking
still works on the first attempt regardless.

## Why the enabled check lives inside the method, not on the annotation

```java
@Scheduled(fixedDelayString = "${inventory.sweeper.fixed-delay-ms:30000}")
public void sweep() {
    if (!enabled) {
        return;
    }
    ...
```

Spring has no built-in way to make a `@Scheduled` method's *schedule itself* conditional on a property
without writing a custom `Trigger` — considerably more machinery than one boolean check needs here. The
annotation stays active regardless of the flag; the body simply declines to act when
`inventory.sweeper.enabled=false`, which is exactly what `LapsedRebookingIT` needs: a sweeper that is
schedulable but proven to do nothing, not one that was never wired up in the first place.

## What verifying this actually found: scheduling itself was silently off

The first version of this class was correct in isolation and would have done nothing in production. The
reason: `@EnableScheduling` — the annotation that switches on Spring's scheduling infrastructure for
the whole application context — was never added to `InventoryServiceApplication`. Without it, a
`@Scheduled` method compiles, runs on no timer at all, and raises no error anywhere to say so.

This had been true since `OutboxRelay` was ported in T133, and went unnoticed because every test
exercising that class so far — including the one built specifically to verify T130 through T133 working
together — called `pollAndPublish()` directly, which bypasses Spring's scheduler entirely. Calling a
`@Scheduled` method's body directly proves the body is correct; it proves nothing about whether Spring
would ever have called it on its own. This class is the first one in this service that actually needed
its own schedule to fire without being told to, which is what made the gap concrete.

The fix — `@EnableScheduling` on `InventoryServiceApplication`, ported from order-service's own
identical annotation and identical reasoning — retroactively fixes `OutboxRelay`'s scheduling too, not
only this new class's.

## Verified with a temporary test that proves the timer, not just the logic

A reservation was planted already-lapsed, `inventory.sweeper.fixed-delay-ms` was shortened to 300ms for
the test, and the assertion polled the database — with **no direct call to `sweeper.sweep()` anywhere
in the test** — until the reservation's status read `EXPIRED`. It did, within the first couple of
cycles. A second temporary test set `inventory.sweeper.enabled=false`, waited several cycles' worth of
real time, and confirmed the reservation stayed exactly `HELD` throughout. Both temporary files have
been removed; what they proved — that scheduling and the enabled flag both genuinely work — is recorded
here rather than only asserted.
