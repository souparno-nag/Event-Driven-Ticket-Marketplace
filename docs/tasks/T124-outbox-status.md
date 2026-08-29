# T124 — `OutboxStatus`, ported

**What this task did:** copied order-service's `OutboxStatus` enum into this service's `outbox`
package, unchanged in content and updated only where its own doc comment points at a different
migration file.

This is the shortest task in this build step, and it's worth being honest about why: there was
nothing to redesign. The whole point of porting rather than rebuilding is that a mechanism proven to
work in one place shouldn't be rethought from scratch the second time it's needed.

---

## Why "just copy it" is the right amount of effort here

`research.md` (R8) already made the decision this task carries out: this service gets its own outbox,
a genuine copy of order-service's rather than a shared library, because two instances of a pattern is
the point where the question "should this be shared?" first becomes visible — not yet the point where
sharing is proven to be the right answer. That reasoning was recorded in the migration itself (T122)
and applies identically here.

Given that decision, `OutboxStatus` specifically has no service-specific behavior to add. `PENDING`,
`PUBLISHED`, and `PARKED` describe a purely mechanical fact — has this row been sent yet, and if not,
has sending it been given up on — and that fact means exactly the same thing whether the row is
carrying `order.created` or `seats.reserved`. There was no design decision left to make; only two
words in the class's own documentation needed updating, to point at *this* service's migration file
(`V4__create_outbox.sql`) instead of order-service's (`V2__create_outbox.sql`).

---

## What the enum actually encodes, restated briefly

Worth restating even though nothing changed: this exists because `published_at IS NULL` alone can only
answer "has this been sent yet?" — a yes-or-no question — and this table needs a *three*-way answer.
A row can be waiting patiently (`PENDING`), successfully sent (`PUBLISHED`), or have failed enough
times that retrying it further would be pointless (`PARKED`). The third state is the one that would be
easy to miss if you only thought in terms of "sent or not sent": a genuinely undeliverable message
needs to eventually stop being retried, and "not yet sent" and "will never be sent" call for entirely
different responses from whoever's operating the system — patience for one, an incident for the other.

This enum is the authority on which of the three a row is in. `published_at` records the *moment* of
publication, not the fact of it — and a `CHECK` constraint in `V4__create_outbox.sql` ties the two
together, so a row can never claim to be `PUBLISHED` while its `published_at` is still null, or vice
versa.

---

## Verifying it

Nothing behavioral to test yet — this is a pure enum, and its actual use begins once `OutboxRecord`
(T130) and the relay (T133) are ported in later tasks. Compiles cleanly as part of the module.
