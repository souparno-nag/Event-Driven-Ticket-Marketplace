# T078 — Proving `@Version` actually works, today

**What this task did:** wrote and ran the test for optimistic locking on `Order`, and unlike the
other five tests in this batch, it needs nothing new — `Order` and `OrderRepository` were already
built in Phase 2. This is the one file in this batch that both compiles and passes right now.

---

## Simulating "concurrent" without real threads

Two threads racing to update the same row would demonstrate the problem, but the outcome would be
timing-dependent — sometimes thread A wins, sometimes B, and a flaky test that only sometimes catches
a real bug is worse than no test. Instead this test gets the same *effect* — a stale copy trying to
overwrite a copy that already moved on — through careful ordering, which is entirely deterministic:

```java
// 1. A copy is read now, before anything changes. It carries version 0.
Order staleCopy = tx.execute(status -> orderRepository.findById(orderId).orElseThrow());

// 2. The "winner": its own fresh read, changed, and committed. version 0 -> 1 in the database.
tx.executeWithoutResult(status -> {
    Order fresh = orderRepository.findById(orderId).orElseThrow();
    fresh.changeStatus(OrderStatus.CONFIRMED);
    orderRepository.saveAndFlush(fresh);
});

// 3. The "loser" tries to save the copy it has been holding since step 1 -- still version 0.
assertThatThrownBy(() -> tx.executeWithoutResult(status -> {
    staleCopy.changeStatus(OrderStatus.CANCELLED);
    orderRepository.saveAndFlush(staleCopy);
})).isInstanceOf(OptimisticLockingFailureException.class);
```

Each step runs in its own transaction, via `TransactionTemplate` — a way to run a block of code
inside a transaction without annotating the whole test method, which matters here because the test
needs *several* separate transactions in sequence, not one.

## What actually happens underneath

Every `Order` carries a `version` number. When Hibernate saves an update, it does not just say
"set these columns" — it says "set these columns, **but only if the version is still what I last
saw**":

```sql
UPDATE orders SET status = ?, version = 1, ... WHERE id = ? AND version = 0
```

The winner's update runs while the database still has `version = 0`, so it matches, succeeds, and
bumps the stored version to `1`. The loser's update — using the *same* `staleCopy` object, whose
`version` field is still `0` because it was read before the winner committed — issues the identical
`WHERE ... AND version = 0`, which now matches **zero rows**, because the real row has moved on to
`1`. Hibernate notices the update affected nothing when it expected one row, and raises the error
that Spring translates into `OptimisticLockingFailureException`.

The alternative, without this column, would be the update simply overwriting whatever was there —
no error, no warning, just one buyer's change silently erasing another's. That silent failure is
exactly what this mechanism, and this test, exist to rule out.

## Confirmed: this test runs today

```text
Tests run: 1, Failures: 0, Errors: 0 -- OrderVersionIT
```

Nothing else in Phase 3 needed to exist for this. `changeStatus()` was already written in T066
specifically so this test would have something to change — its own javadoc says so — and
`OrderRepository` has needed nothing beyond what T068 already gave it. This is the one piece of User
Story 1 that was provably correct before a single line of the acceptance flow existed.
