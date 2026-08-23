# T064 — The `OrderStatus` enum

**What this task did:** created the three-value enum naming where an order sits in its lifecycle.

```java
public enum OrderStatus { PENDING, CONFIRMED, CANCELLED }
```

---

## Why declare states nothing can reach

Only `PENDING` is reachable in this build step. Nothing moves an order out of it, because the
services that would — inventory in step 3, payment in step 4 — do not exist yet.

Both other constants are declared anyway, and the reason is the database. The migration from T062
carries:

```sql
CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED'))
```

A `CHECK` constraint rejects any value not in its list. Had it listed only `PENDING`, then the day
step 4 first confirms an order, the write would be refused — at exactly the moment the saga finally
runs end to end, and with an error pointing at the database rather than at the missing migration.

Declaring all three now costs nothing and removes that trap. The enum and the constraint have to
agree, so they are written together.

## The lifecycle they describe

```text
                    ┌──────────────────────────────┐
   POST /api/orders │                              │
   ────────────────►│           PENDING            │
                    └──────┬───────────────┬───────┘
                           │               │
       payment succeeded   │               │  seats rejected, or payment failed
            (step 4)       │               │           (step 5)
                           ▼               ▼
                    ┌────────────┐   ┌────────────┐
                    │ CONFIRMED  │   │ CANCELLED  │      both terminal
                    └────────────┘   └────────────┘
```

`PENDING` is worth reading carefully: **accepted and recorded, but no seats held and no money
moved**. This is why the API answers `202 Accepted` rather than `201 Created` — the order exists,
the booking does not.

---

## Storing the name, not the number

The entity stores this as `EnumType.STRING`, so the database holds `'PENDING'`.

The alternative, `EnumType.ORDINAL`, stores the constant's **position** — 0, 1, 2. It is smaller and
it is a well-known way to lose data. Insert a new constant into the middle of the list a year from
now:

```java
public enum OrderStatus { PENDING, RESERVED, CONFIRMED, CANCELLED }
```

Every row that said `2` meant `CANCELLED` and now means `CONFIRMED`. No error, no warning, no
migration failure — just orders that quietly changed their outcome. Storing the name makes reordering
harmless, and makes `SELECT * FROM orders` readable while you are at it.
