# T054 — Writing down what replication factor 1 actually costs

**What this task did:** recorded, as a `TRADEOFF:` comment in `infra/docker-compose.yml`, that the
message channels are created with **replication factor 1** — and that this is a consequence of
running one broker, not a topology anyone should copy.

It also fixed something found while doing it: **two comments claimed the README explained this, and
the README did not mention it at all.**

---

## What replication factor means

A Kafka topic is split into partitions, and each partition can be copied onto other brokers. The
**replication factor** is how many copies exist.

- **Factor 3** — three brokers each hold a copy. One can fail and no data is lost; a follower is
  promoted and the cluster keeps serving.
- **Factor 1** — one copy, on one broker. If that broker's storage is lost, **every message is
  lost**, and no consumer can recover them.

This environment runs factor 1. `--describe` says so plainly:

```
Topic: order.created   PartitionCount: 3   ReplicationFactor: 1
  Partition: 0   Leader: 1   Replicas: 1   Isr: 1
```

`Isr: 1` — in-sync replicas — is the same fact from the other side: there is one copy and it is the
leader.

## Why it is not really a decision

There is one broker. A replication factor above 1 needs *other brokers* to put the copies on, and
Kafka does not degrade gracefully here — topic creation **fails outright** with a message about
insufficient brokers. Asking for factor 3 on a single node does not get you a best-effort two
copies; it gets you no topic.

So factor 1 is what a single-node cluster can express, and the interesting question is whether a
single node is the right thing to run.

## Why it is right here

- The data is **generated demo state**. Nothing in these channels is worth recovering.
- `make down` **deletes the volumes on purpose** (FR-015). The environment is designed to be thrown
  away and rebuilt; ten consecutive cycles of exactly that are what T043 verified.
- Redundancy would cost **two more brokers at roughly 768 MiB each** — tripling the memory budget,
  on a laptop, to protect data that is discarded on every teardown.

A production deployment inverts all three of those, and would run at least three brokers with
replication factor 3 and `min.insync.replicas=2`, so a broker can fail without losing acknowledged
writes. That number is in the comment too, because "not production" is only useful advice if it says
what production would do instead.

---

## Why the comment goes where it does

It sits on the **`kafka-init` service** — the thing that actually creates the channels with that
setting. The broker's own internal topics (`__consumer_offsets`, the transaction state log) already
carried a similar note higher up in the file, but those are a different set of topics for a
different reason: they default to factor 3 and would fail to create on one broker, which is about
the broker starting at all rather than about the saga's messages.

Putting the note next to the thing it describes is the general rule. A comment about channel
replication attached to the broker configuration would be findable only by someone who already knew
to look.

---

## The dangling reference

Both the compose file and `create-topics.sh` said some version of *"not a production topology, and
the README says so"*. Checking:

```bash
$ grep -i 'replication' infra/README.md
>>> NOT MENTIONED
```

The comments pointed at documentation that did not exist. That is a small failure with an outsized
effect: a reader who follows the pointer and finds nothing loses confidence in every other pointer
in the file, and cross-references are only worth writing if they are reliable.

Two ways to fix it — delete the claim, or make it true. Making it true was clearly better here,
because `--describe` genuinely shows `ReplicationFactor: 1` to anyone inspecting a channel, and the
operator documentation is exactly where that person should be able to find out why.

So `infra/README.md` gained a **Message channels** section: the fourteen channels, how to list and
describe them, a table explaining three partitions and factor 1, and the full constraint written out
for a reader who is not reading source comments. The script's comment now points at that section by
name.

---

## In one line

Replication factor 1 is what one broker can express, not a recommendation — and the comments that
promised the README explained it now point at a section that actually does.
