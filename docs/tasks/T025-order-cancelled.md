# T025 — `OrderCancelled`, and the build goes green

**What this task did:** created `OrderCancelled.java`, the seventh and last record. With it, the
four test files compile for the first time and **all 31 tests pass**.

---

## The message

Every failure path converges here:

```
             SeatsRejected ──────────────┐
                                         ├──► OrderCancelled ──► inventory releases any hold
             PaymentFailed ──────────────┘
        (and later, an expired hold in step 4)
```

Order-service publishes it; inventory-service consumes it and releases whatever it is holding for
that order.

### Why one message rather than each service reacting to each failure

Inventory-service could watch `PaymentFailed` directly and release on that. It does not, and the
reason generalises.

**The order service owns the decision that an order is over.** Everyone else reacts to that single
decision. If each consumer interpreted each failure for itself, the conclusion "this order is dead"
would be implemented in several services — and the day a fourth failure mode is added, some of them
would be updated and some would not. Funnelling through the aggregate's owner means that conclusion
is reached in exactly one place.

There is a second benefit: the terminal state is *observable*. One channel carries every ended
order, whatever killed it, which is what a projection or an audit needs.

---

## Two deliberate omissions

**No seat list.** `OrderConfirmed` carries one, this does not — and T024 argued that a message
should stand alone, so the asymmetry needs justifying.

The release is keyed by order: inventory looks up what it holds for that `orderId` and releases it.
It is the holder of record. If this message also stated the seats, the two could disagree — and if
they did, the message would be wrong, because inventory knows what it actually locked. A field that
can only ever be redundant or wrong is not worth having.

And on the `SEATS_UNAVAILABLE` path there is genuinely no list to state, because no hold was taken.

**No detail about the underlying failure.** Every kind of payment failure — declined, timed out,
provider broken — collapses into the single value `PAYMENT_FAILED`.

That detail lives on `PaymentFailed`, where it is actionable. Here it is not: the seats are released
identically whichever it was. This is the boundary T010 described — step-level vocabulary owned by
the service that publishes it, order-level vocabulary owned by order-service — and passing along
detail no consumer acts on is how contracts bloat.

---

## The seven records are done

```
OrderCreated       9 components   the request
SeatsReserved      8              the hold, with its expiry
SeatsRejected      7              the refusal
PaymentSucceeded   7              the charge
PaymentFailed      6              the failure
OrderConfirmed     6              the happy ending
OrderCancelled     6              every other ending
```

Every one: four envelope components, then its own facts, validated in a compact constructor,
implementing `SagaEvent`, no behaviour, no framework.

---

## Red to green

```
./mvnw -pl common-events clean test

Tests run: 31, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

Four test files written before any of these records existed, and they passed the moment the last one
landed. That is the payoff of the tests-first ordering: the record shapes were decided by the tests
that use them, not the other way round.

## But: a test that has never failed proves nothing

Green on the first run should provoke a little suspicion. Passing tests are evidence only if the
tests *can* fail — and a test that has never been observed failing might be asserting nothing at all.
The vacuous reflective test T018 guards against is exactly this hazard.

So I mutated the code deliberately and checked the suite noticed. Two changes, each reverted
immediately:

**Mutation 1 — delete the `sagaId`/`orderId` check from `OrderCreated`:**

```
[ERROR] ValidationTest$SagaCorrelation.rejects_a_saga_id_that_does_not_match_the_order
        Tests run: 1, Failures: 1
```

**Mutation 2 — rename `showId` back to `eventId`, the original bug:**

```
[ERROR] NamingConventionTest.no_component_uses_the_word_event
[ERROR] NamingConventionTest.message_id_and_show_id_are_distinct
        Tests run: 4, Failures: 2
```

The second is the more satisfying result. The reflective scan really does walk the compiled package
and really does catch the naming regression — including on a record it was never told about, since
nothing registers `OrderCreated` with that test. Had the scan silently found nothing, both would have
passed.

This technique has a name — **mutation testing** — and doing it by hand for a few minutes is a
reasonable substitute for tooling. Whenever a suite goes green on its first run, it is worth breaking
something on purpose to confirm the suite is watching.

---

## Try it yourself

```bash
./mvnw -pl common-events clean test
```

**Expect**: `Tests run: 31, Failures: 0, Errors: 0`.

Try a mutation of your own — the money scale rule is a good one, since it is the least intuitive:

```bash
# in Validation.java, change   amount.scale() != 2   to   amount.scale() > 4
./mvnw -pl common-events test
```

**Expect**: `ValidationTest$Money` failures for both the too-few and too-many decimal cases. Revert
with `git checkout common-events/src/main/java/com/marketplace/events/Validation.java`.

---

## What comes next

**T026** seals `SagaEvent`. T012 had to leave the interface open because Java requires the types in a
`permits` clause to exist — now they do, so the compiler can be told these seven are the complete
set, and a `switch` over saga messages becomes exhaustively checked.

**T027** adds the `TRADEOFF:` comment recording why the envelope is duplicated across the seven
records rather than extracted into a wrapper.

**T028** verifies no framework leaked into the module, which closes User Story 1 — the MVP of this
whole build step.
