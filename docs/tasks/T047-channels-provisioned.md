# T047 — Scenario 5: the channels are really there (SC-009, FR-020, FR-021, FR-027)

**What this task did:** started the environment and checked the message channels against a running
broker rather than against a file. **All four checks passed**, and this is the first time the
provisioning wiring from T044 and T045 ran for real.

| Check | Requirement | Result |
|---|---|---|
| Fourteen channels exist | FR-020, SC-009 | ✅ exactly 14 |
| Three partitions each | FR-027 | ✅ `PartitionCount: 3` |
| Replication factor 1 | single-broker constraint | ✅ `ReplicationFactor: 1` |
| Re-running startup succeeds | FR-021 | ✅ second `make up` clean |

---

## The provisioning step ran as designed

```
✔ Container kafka     Healthy    6.2s
✔ Container postgres  Healthy    5.7s
✔ Container redis     Healthy    5.7s
[+] Creating 1/1
 ✔ Container kafka  Running
Provisioning channels on kafka:29092 (partitions=3, replication=1)
  order.created
  order.created.DLT
  ...
14 channels ready
```

Read the order carefully, because it is the thing T045 was built to get right: all three components
reach **healthy** first, and only then does the job connect. Channels cannot be created before the
broker can serve metadata, and this is the environment's one genuine ordering dependency (T038)
being honoured — `--wait` gates the components, `depends_on: service_healthy` gates the job.

`make up` then **exited 0**. That was not free: a container that exits is neither running nor
healthy, and had the job been left inside the `--wait` set it would have failed this command despite
succeeding. Running it through `compose run` instead is what makes a successful startup report
success.

---

## The channels themselves

```
$ kafka-topics --bootstrap-server localhost:9092 --list | sort
order.cancelled          order.cancelled.DLT
order.confirmed          order.confirmed.DLT
order.created            order.created.DLT
payment.failed           payment.failed.DLT
payment.succeeded        payment.succeeded.DLT
seats.rejected           seats.rejected.DLT
seats.reserved           seats.reserved.DLT
```

Fourteen, counted directly:

```
$ kafka-topics ... --list | grep -cE '^(order|seats|payment)\.'
14
```

Seven message types, each paired with its own dead-letter channel. **SC-009 satisfied**: a freshly
started environment exposes exactly fourteen channels with no manual provisioning.

Worth noting what is *absent*: `__consumer_offsets`, Kafka's internal bookkeeping topic, does not
appear. It is created lazily when the first consumer group forms, and no consumer exists yet. It
will show up from build step 2 onward, which is why the counting command filters by prefix rather
than counting every line — a check that breaks the moment the system starts being used is not much
of a check.

---

## The partition count, which no unit test could have asserted

```
Topic: order.created  PartitionCount: 3  ReplicationFactor: 1
  Partition: 0  Leader: 1  Replicas: 1  Isr: 1
  Partition: 1  Leader: 1  Replicas: 1  Isr: 1
  Partition: 2  Leader: 1  Replicas: 1  Isr: 1
```

This is the check T046 explicitly could not make. The drift test proves the two files **name** the
same channels; it says nothing about the settings those channels are created with, because
partitions do not exist in a file — they exist on a broker.

**Why three matters.** A partition is the unit of parallelism: one consumer reads each partition at
a time, so three partitions allow three orders to be processed concurrently. Kafka guarantees
ordering *within* a partition, not across a topic, and messages are keyed by `sagaId` — so every
message for one order lands on the same partition and stays ordered, while different orders spread
across partitions and proceed independently. That is exactly the guarantee the saga needs, and it is
why T029 disabled the broker's auto-creation: auto-created topics get **one** partition, and the
system would work perfectly while having no concurrency at all.

`ReplicationFactor: 1` with `Isr: 1` (in-sync replicas) confirms the single-broker reality: one
copy, no redundancy. A local constraint rather than a design choice, and the README says so.

---

## Idempotency — the check most likely to be skipped

Running `make up` a second time **without tearing down**:

```
✔ Container kafka     Healthy   0.5s
✔ Container postgres  Healthy   0.5s
✔ Container redis     Healthy   0.5s
Provisioning channels on kafka:29092 (partitions=3, replication=1)
  ...
14 channels ready
```

Clean, and fast — 0.5s, because everything was already running and `--wait` simply confirmed it.

This is `--if-not-exists` doing its job (FR-021). Without it, the second run would have died on
`TopicExistsException`: an error for doing nothing wrong. **Startup has to tolerate having already
happened**, because people re-run it constantly — after editing a compose file, after a laptop
sleeps, in the middle of debugging something else. A startup that only works from a clean slate
makes every one of those an obstacle.

It also matters that the script's final count re-ran and still reported 14. The idempotent path is
verified, not just tolerated.

---

## `make health` stayed honest

```
$ make health
  ok    kafka            healthy
  ok    postgres         healthy
  ok    redis            healthy
```

Three lines. **No fourth line for `kafka-init`** — which is the T045 design decision paying off in
the place it was predicted to. A job that has finished is not a component whose health can be
reported. Because it lives in a profile nothing activates, `config --services` never offers it to
the health target, and no special case was needed.

Had it been tagged `core`/`full` as originally planned, this output would have needed one, forever.

---

## Teardown

```
$ make down
✔ Container kafka / redis / postgres   Removed
✔ Volume  kafka-data / postgres-data   Removed
✔ Network ticket-marketplace_default   Removed
```

Six resources, nothing left behind — the `--profile '*'` fix from T042 continuing to hold.

---

## What it demonstrates

- **FR-020**: one channel per message type plus a paired dead-letter channel, created with no manual
  provisioning step. ✅
- **FR-021**: channel creation is idempotent, succeeding when the channels already exist. ✅
- **FR-027**: channels have more than one partition. ✅ three.
- **SC-009**: a freshly started environment exposes exactly fourteen channels, verified by listing
  them. ✅

---

## In one line

The channels exist, with the partition count that makes concurrency possible, and creating them
twice is not an error — checked against a running broker, which is the only place any of those three
claims can honestly be made.
