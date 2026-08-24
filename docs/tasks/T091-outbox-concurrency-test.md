# T091 — Specifying that no relay ever sends someone else's row

**What this task did:** wrote the test for guarantee 11 — three relays polling the same database
over a thousand rows, and not one of those rows ever sent twice.

---

## Confirmed to fail, for the right reason

```text
OutboxConcurrencyIT.java:[44,17] cannot find symbol
  symbol:   class OutboxRelay
```

Same missing class, verified in isolation alongside the batch's other `OutboxRelay`-dependent files.

## Three threads on one bean, not three application instances

The spec talks about "three service instances relaying against one store" (SC-006). This test gets
the same database-level behaviour a different, far cheaper way:

```java
ExecutorService pool = Executors.newFixedThreadPool(RELAY_THREADS);
for (int i = 0; i < RELAY_THREADS; i++) {
    futures.add(pool.submit(this::drainUntilEmpty));
}
```

Three threads, calling `pollAndPublish()` on the **same** `OutboxRelay` bean. That is not a
simplification that misses the point — `pollAndPublish()` is `@Transactional`, and Spring gives every
concurrent call its own transaction regardless of how many threads or how many application instances
are making the call. The property genuinely under test — can the claim query (T094) let two
transactions see the same row at once — is a database question, not a question about how many JVMs
exist. Three real Spring Boot instances would exercise the identical PostgreSQL behaviour, at the
cost of starting three application contexts instead of none extra.

## Draining, not polling once

A single `pollAndPublish()` call only claims up to `batch-size` (100) rows. Proving no row is ever
double-sent across **1,000** rows means each of the three threads has to keep calling it until nothing
is left:

```java
private void drainUntilEmpty() {
    while (pendingCount() > 0) {
        outboxRelay.pollAndPublish();
    }
}
```

`pendingCount()` reads directly via `JdbcTemplate` rather than through `OutboxRepository`, which
stays deliberately untouched in this batch — `claimBatch`, the one method this table's repository is
missing, belongs to T094, and a test asking "how many rows are still pending" is a different,
much simpler question than "claim me some rows," answerable with a plain SQL count that needs no
change to production code at all.

## The direct check, not a proxy for it

```java
Map<String, Long> countsByKey = consumed.stream()
        .collect(Collectors.groupingBy(ConsumerRecord::key, Collectors.counting()));
assertThat(countsByKey.values()).allSatisfy(count -> assertThat(count).isEqualTo(1L));
```

Every row in this test carries a distinct, randomly generated key, so counting how many times each
key appears on the channel — after all 1,000 rows have gone through three racing relays — is exactly
guarantee 11 stated as an assertion: not "the total count came out right" (which would still pass if
one row were sent twice and another lost), but that **every individual row** was sent **exactly**
once, checked one key at a time.
