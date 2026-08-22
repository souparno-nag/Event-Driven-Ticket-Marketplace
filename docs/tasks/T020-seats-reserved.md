# T020 — `SeatsReserved` and the fencing field

**What this task did:** created `SeatsReserved.java`. It is the only record with a validation rule of
its own, and that rule guards the worst bug a ticketing system can have.

---

## The message

Inventory-service has held every requested seat. Payment-service consumes this and charges the card.

```
OrderCreated ──► inventory holds seats ──► SeatsReserved ──► payment charges
```

Beyond the envelope it carries `orderId`, `seatIds`, `reservationId`, and `lockExpiresAt`.

**`seatIds` is exactly what was requested — never a subset.** Holds are all-or-nothing. Asking for
four seats where three are free does not produce a hold on three; it produces a `SeatsRejected` for
the whole request. Partial fulfilment would mean a customer paying for seats they did not choose, or
a group split across the venue, and it makes the compensation logic dramatically harder — you would
have to track *which* seats were held per order rather than "all of them or none".

**`reservationId`** names the durable reservation row, so the later commit or release acts on a
specific thing rather than re-deriving it from the order.

---

## Why seat holds expire at all

A hold is temporary — a couple of minutes. That is not an optimisation, it is a necessity: without
expiry, a customer who opens checkout and wanders off keeps four seats unsellable forever. Every
system that reserves scarce inventory does this, which is why a booking page shows you a countdown.

The consequence is that **a hold can lapse mid-saga**, and that is where the danger lives.

## The bug `lockExpiresAt` exists to prevent

```
t=0     seats A12, A13 held for order-1, expiring at t=120
t=5     payment-service starts charging
        ... payment provider is slow, or a consumer restarts, or a partition is rebalancing ...
t=125   hold lapses. A12 and A13 are free again.
t=130   customer B buys A12 and A13. Legitimately.
t=140   payment for order-1 finally succeeds
t=141   order-1 confirms A12 and A13   ← two people now own seat A12
```

Nothing here is a crash, and no component behaved incorrectly in isolation. The saga simply took
longer than its own hold lasted, and the last step acted on a claim that had already expired.

The defence is to carry the expiry with the message so the confirming service can check it:

```java
if (Instant.now().isAfter(reserved.lockExpiresAt())) {
    cancel(CancellationReason.RESERVATION_EXPIRED);   // do NOT confirm
}
```

This is called **fencing** — carrying a token or deadline that proves a claim is still valid, so a
slow actor cannot act on a stale one. It is the same idea as a fencing token in distributed locking,
and it is the standard answer to "what if the process holding the lock stalls?"

Note what makes it work: the check happens at the point of use, against data carried in the message,
rather than by asking inventory-service "is my hold still good?" A callback would be a synchronous
dependency *and* would still race — the answer could go stale between the reply and the confirm.

Nothing consumes `lockExpiresAt` in this build step. It is here now because adding it later would
mean versioning a contract that six services already read (**FR-008**), the same reasoning that put
`RESERVATION_EXPIRED` in T010's enum before anything produced it.

---

## The rule: *strictly* after

```java
if (!lockExpiresAt.isAfter(occurredAt)) {
    throw new IllegalArgumentException(...);
}
```

Two decisions here.

**Why strict.** A hold expiring at the exact instant it was taken is already expired — the fencing
check `now > lockExpiresAt` could never pass for it. Requiring `lockExpiresAt > occurredAt` rather
than `>=` makes that message impossible to construct. This is the boundary T017 tests from both
sides: expiry before `occurredAt`, and expiry equal to it.

Off-by-one at a boundary is the most common way a rule is subtly wrong, and it is invisible unless
something sits exactly on the edge.

**Why it is not in `Validation`.** Every other rule is a helper in T014's file. This one stays here
because it is the only invariant relating **two components of one record**. A generic
`requireAfter(Instant, Instant)` would be reusable and would communicate nothing — the reader learns
that one instant follows another, not that a seat hold must outlive the message announcing it. Rules
that are specific to a type belong with the type; rules shared across types belong in the shared
place. Putting everything in the shared place because it is the validation file is how a helper
class becomes a junk drawer.

---

## A note on clocks

`occurredAt` and `lockExpiresAt` are both produced by inventory-service, from the same clock, so
comparing them is safe.

It would *not* be safe to compare timestamps from two different services. Machine clocks drift, and
NTP corrections can move a clock backwards; a message can genuinely appear to arrive before it was
sent. This is why distributed systems avoid ordering events by wall-clock time and use sequence
numbers, partition offsets, or logical clocks instead — and why per-order ordering in this system
comes from Kafka partitioning (T012's `sagaId` key), never from `occurredAt`.

The fencing check does compare across machines: payment-service compares its own `Instant.now()`
against inventory-service's `lockExpiresAt`. That is tolerable because the hold is measured in
minutes while clock drift under NTP is milliseconds — a two-order-of-magnitude margin. Worth knowing
the assumption exists, though. If holds were measured in milliseconds, this design would not work.

---

## Try it yourself

```bash
./mvnw -pl common-events compile
```

**Expect**: `BUILD SUCCESS`, 8 source files. Five records still to go before the tests compile.

The clock comparison is easy to try:

```bash
jshell
```

```java
import java.time.Instant;
var t = Instant.parse("2026-08-22T09:15:30Z");
t.isAfter(t)
t.plusSeconds(120).isAfter(t)
```

**Expect**: `false`, then `true`. The first is the case the strict rule rejects — and the reason
`isAfter` is the right method rather than `!isBefore`, which would accept equality.

---

## What comes next

**T021** — `SeatsRejected`, the shortest path through the saga: the seats could not be held, so the
order is cancelled immediately with nothing to compensate.
