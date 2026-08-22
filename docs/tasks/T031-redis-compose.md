# T031 — Redis, and the most consequential line in the file

**What this task did:** added the `redis` service. One of its settings is the difference between a
working ticket marketplace and one that sells the same seat twice.

---

## What Redis is doing in this system

Redis holds **seat locks**. When an order comes in for seats A12 and A13, inventory-service writes:

```
seat:{showId}:A12  →  orderId     TTL 120s
seat:{showId}:A13  →  orderId     TTL 120s
```

Two properties make Redis right for this, and both would be awkward in PostgreSQL:

- **It is fast.** Every booking attempt checks these keys, and the step-9 load test throws 1000
  concurrent users at ten seats. This is the hot path.
- **Keys expire on their own.** `TTL 120s` means an abandoned checkout releases its seats without
  anyone running a cleanup job. In PostgreSQL you would write that job, schedule it, and debug it
  when it fell behind.

So the split is: **Redis answers "is this seat free right now?", PostgreSQL answers "what did we
promise?"** Redis is the fast authority on current contention; Postgres is the durable record. That
division is why both are in the stack rather than one doing double duty.

---

## The eviction policy, which is a correctness setting

```yaml
- --maxmemory
- 64mb
- --maxmemory-policy
- noeviction
```

Redis's *default* behaviour when it hits `maxmemory` is to evict — throw away some existing key to
make room for the new one. For a cache, that is exactly right: a cache miss costs a slow lookup and
nothing more.

**Here, an evicted key is a released seat lock.**

Walk the consequence through:

```
1. Seat A12 is locked for order-1.
2. Redis reaches 64 MB under load.
3. Redis evicts an old key to make room.  ← seat:show-9:A12 happens to be it
4. Order-2 checks A12. No lock found. Seat looks free.
5. Order-2 locks A12 and proceeds to payment.
6. Two customers now own seat A12.
```

There is no error at any step. Redis did what it was configured to do. The lock did not fail — it
*disappeared*, and nothing distinguishes "never locked" from "locked and then quietly evicted".

`noeviction` changes step 3: Redis **refuses the write** and returns an error. The booking fails,
loudly, and the customer sees "please try again".

That framing is worth keeping:

> When your two failure modes are **"reject a booking"** and **"sell one seat twice"**, you choose
> the first every single time.

An eviction policy sounds like tuning. On data that represents an exclusive claim, it is a
correctness decision — and it is why this line has the longest comment in the Compose file.

---

## Persistence is off, deliberately

```yaml
- --save
- ""
```

By default Redis periodically snapshots to disk so data survives a restart. Turned off here, for
reasons that follow directly from what the data *is*:

- **Seat locks are worthless after a restart.** They carry a 120-second TTL. Anything restored from
  a snapshot is either about to expire or already stale, and a *stale* lock is actively harmful —
  it holds a seat for an order that no longer exists.
- **The durable record lives in PostgreSQL.** A confirmed order's reservation is committed there.
  That is the whole point of splitting the two stores: losing Redis loses in-flight holds, not
  sales.
- **Snapshotting has a memory cost.** Redis `fork()`s to write a snapshot, and copy-on-write can
  briefly double its footprint. Under a 96 MiB cap that is a real risk of exit code 137 during
  ordinary operation.

The general principle: **match durability to what the data means.** Not everything needs to survive
a restart, and paying for durability you do not need costs memory, latency, and the risk of
restoring something you would rather have lost.

---

## Why `PING` is a fair readiness check

```yaml
test: ["CMD", "redis-cli", "ping"]
```

T030 made a fuss about `pg_isready` versus a port check, because PostgreSQL accepts connections only
after crash recovery. Redis has no equivalent phase.

More usefully: **Redis is single-threaded.** Commands are processed one at a time, so a `PONG` means
the server got to your command and answered it. There is no state where it responds while unable to
work — if it were busy with a long-running command, `PING` would not come back either.

Same principle as everywhere else in this file, different mechanics: ask the component the question
that a real client asks.

---

## Try it yourself

```bash
docker compose -f infra/docker-compose.yml --profile core up -d redis
docker exec redis redis-cli config get maxmemory-policy
```

**Expect**: `noeviction`. Worth confirming — this is the setting that would be catastrophic to get
wrong, and a typo in the flag would leave the default silently in place.

You can watch `noeviction` behave, which makes the argument concrete:

```bash
docker exec redis redis-cli config set maxmemory 1mb
docker exec redis redis-cli eval "for i=1,200000 do redis.call('set', 'filler:'..i, string.rep('x', 100)) end return 1" 0
```

**Expect**: an error like `OOM command not allowed when used memory > 'maxmemory'`. That is the
refusal — the write fails instead of something else being deleted to make room.

Now contrast with the cache behaviour:

```bash
docker exec redis redis-cli config set maxmemory-policy allkeys-lru
docker exec redis redis-cli dbsize
docker exec redis redis-cli eval "for i=1,200000 do redis.call('set', 'filler:'..i, string.rep('x', 100)) end return 1" 0
docker exec redis redis-cli dbsize
```

**Expect**: no error at all, and `dbsize` roughly unchanged or lower — Redis quietly deleted old keys
to fit the new ones. **That silence is the bug.** Under `noeviction` you get an error; under
`allkeys-lru` you get a double-booking three weeks later.

Reset when done:

```bash
docker compose -f infra/docker-compose.yml --profile core restart redis
```

(The config changes were runtime-only; the restart restores what the Compose file specifies.)

---

## What comes next

**T032** — Elasticsearch, whose health check has to accept `yellow` as success. Insisting on `green`
on a single node is a trap that hangs startup forever.
