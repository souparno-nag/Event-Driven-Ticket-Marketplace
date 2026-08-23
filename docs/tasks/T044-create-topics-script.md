# T044 — `create-topics.sh`, the fourteen channels

**What this task did:** wrote `infra/kafka-init/create-topics.sh`, which creates one Kafka topic per
message type plus a dead-letter topic paired with each — **fourteen channels** — and verifies the
count before exiting.

---

## What a topic is, and why they must exist in advance

A Kafka **topic** is a named channel. A producer writes messages to a topic; consumers read from
it. This project uses topic-per-message-type, so `OrderCreated` messages go to `order.created`,
`SeatsReserved` to `seats.reserved`, and so on — the names being the constants already defined in
`Topics.java`.

A reasonable question is why they need creating at all. Kafka *can* create a topic automatically the
first time somebody references one — and T029 deliberately turned that off:

```yaml
KAFKA_AUTO_CREATE_TOPICS_ENABLE: "false"
```

The reason is that auto-creation uses **default settings**, which means **one partition**. The
system would work. Nothing would error. It would simply have no concurrency, which is precisely what
the load test in build step 9 is supposed to prove. That is the worst shape a bug can take: silent,
plausible, and invisible until the measurement that mattered gives a wrong answer.

So channels are created explicitly, with their settings stated.

---

## The three settings

```bash
kafka-topics --bootstrap-server "$BOOTSTRAP" \
  --create --if-not-exists \
  --topic "$topic" \
  --partitions 3 \
  --replication-factor 1
```

**`--partitions 3` (FR-027).** A partition is the unit of parallelism: one consumer can read each
partition at a time, so three partitions allow three orders to be processed concurrently. Kafka
guarantees ordering *within* a partition, not across a topic — which is exactly the guarantee this
system needs, because messages for one order must stay ordered while different orders are free to
interleave. That works because messages are keyed by `sagaId`, and Kafka routes a given key to the
same partition every time.

**`--replication-factor 1`.** A replica is a copy on another broker. There is one broker here, so
anything above 1 fails outright — there is nowhere to put the copy. This is a local constraint, not
a design choice, and the README says so.

**`--if-not-exists` (FR-021).** This is what makes the script **idempotent** — safe to run any
number of times. Without it, the second `make up` against an environment whose volumes survived
would fail with `TopicExistsException`: an error for doing nothing wrong. Startup has to be
re-runnable, so every step in it has to tolerate having already happened.

---

## Dead-letter channels, one per message type

Each of the seven gets a partner: `order.created` and `order.created.DLT`.

A dead-letter channel is where a message goes when it cannot be processed after its retries are
exhausted. The alternative to having one is worse than it sounds — the consumer either drops the
message (data loss) or retries it forever (a **poison message**, blocking its partition and
everything queued behind it).

Seven separate DLTs rather than one shared one (FR-025) because a shared channel mixes unrelated
failures: anything draining it would have to re-derive what each message was, and a flood of
failures in one flow would bury a single important failure in another. Keeping the partner channel
paired with its type preserves the context for free.

---

## The counting check at the end

Every `kafka-topics --create` above could succeed while the broker held the wrong channels. So the
script does not trust its own steps:

```bash
expected=$(( ${#TOPICS[@]} * 2 ))
actual=$(kafka-topics --bootstrap-server "$BOOTSTRAP" --list | grep -c -E '^(order|seats|payment)\.')
[ "$actual" -eq "$expected" ] || { echo "FAILED: expected $expected, found $actual" >&2; exit 1; }
```

`--if-not-exists` is precisely the flag that makes this necessary: it reports success for a channel
that already exists **with the wrong partition count**. Asserting the end state catches what
checking each step cannot.

`set -euo pipefail` at the top does the rest of the work — without `-e`, a failing create in the
middle would be printed and then ignored, and the script would cheerfully exit 0.

---

## Two details that would have failed at runtime

**The broker address is `kafka:29092`, not `localhost:9092`.** This script runs *inside* a
container on the Compose network, where `localhost` is that container itself. `29092` is the
INTERNAL listener the broker advertises to other containers; `9092` is the HOST listener for
processes outside Docker. Using the wrong one gives a connection refused that reads exactly like a
broker that is down, while the broker is perfectly healthy.

**The command is `kafka-topics`, not `kafka-topics.sh`.** Checked rather than assumed:

```bash
$ docker run --rm --entrypoint sh confluentinc/cp-kafka:7.7.1 -c 'command -v kafka-topics'
/usr/bin/kafka-topics
```

Apache's own Kafka distribution ships `kafka-topics.sh` in `bin/`; Confluent's image installs
wrapper scripts without the extension. `quickstart.md` used the `.sh` form in Scenario 5 and has
been corrected — otherwise T047's verification would have failed on `command not found` and looked
like a provisioning bug rather than a typo.

The same check confirmed `bash` is present in the image, which the script's arrays and
`set -o pipefail` need.

---

## The cost of this approach, named up front

The seven channel names now exist in **two places**: this script and `Topics.java`. Nothing enforces
that they agree. Rename a constant in Java and this script will keep creating the old channel
perfectly successfully, while the application publishes to one that does not exist.

That risk is recorded as a `TRADEOFF:` in the script itself. The alternative — generating the list
from the compiled jar — was rejected because it makes starting the environment depend on a build
having happened, which is the chicken-and-egg that T045 explains at length.

The plan already anticipated this: **T046** adds a test pinning `Topics.ALL` at seven entries and
checking that `Topics.dlt()` produces the same `.DLT` suffix this script uses, which closes the gap
from the Java side.

---

## In one line

Fourteen channels, created explicitly because Kafka's automatic creation would silently give them
one partition each, made re-runnable with `--if-not-exists`, and verified by counting rather than by
trusting the commands that ran.
