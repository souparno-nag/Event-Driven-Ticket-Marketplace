# T065 — The `OutboxStatus` enum

**What this task did:** created the enum naming where an outbox record sits between being written
and being sent.

```java
public enum OutboxStatus { PENDING, PUBLISHED, PARKED }
```

---

## Why an enum rather than a timestamp check

The original sketch of the outbox table had no status column. Whether a record was outstanding was
answered by a question about `published_at`:

```sql
WHERE published_at IS NULL      -- not sent yet
```

That is a perfectly good way to express **two** states. This table needs **three**.

## The third state

Consider a record whose send fails every single time — the payload is malformed, or it names a
channel that no longer exists. With only two states there is no way to record "we have stopped
trying", so the relay retries it forever.

Two things go wrong, and the quiet one is worse:

1. Work is burned repeatedly on something that will never succeed.
2. Because messages for a single order must be sent **in the order they were recorded**, the stuck
   record blocks every later message for that buyer. Their booking simply stops. Nothing raises a
   hand, because from the outside the relay looks busy and healthy.

`PARKED` is the state that makes this visible. After a set number of failures the relay stops
retrying, the record is kept with the reason it failed, and a metric reports how many parked records
exist. A parked record is an incident, not a background nuisance — which is exactly why it needs a
name.

## The transitions

```text
                          send succeeds
              ┌──────────────────────────────────►┌───────────┐
              │                                   │ PUBLISHED │  terminal
   ┌──────────┴──┐                                └───────────┘
   │   PENDING   │◄──┐ send fails, attempts < limit
   └──────────┬──┘   │ (attempts++, last_error recorded)
              │      └──────────────────────────┐
              │                                 │
              └────────────────────────────────►┴──►┌────────┐
                        send fails,                 │ PARKED │  terminal without a human
                        attempts = limit            └────────┘
```

---

## How it relates to `published_at`

Both are kept. `status` is authoritative — it answers *what state is this in*. `published_at`
answers *when was it sent*, which is genuinely useful when working out how far the relay has fallen
behind.

Carrying the same fact in two places invites them to disagree, so the database forbids it:

```sql
CHECK ((status = 'PUBLISHED') = (published_at IS NOT NULL))
```

Read it as: these two are true together or false together, never one without the other.
