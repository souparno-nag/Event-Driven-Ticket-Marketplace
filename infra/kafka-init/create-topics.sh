#!/usr/bin/env bash
#
# Creates every message channel the saga needs, plus a dead-letter channel paired with each one.
# Seven message types, fourteen channels (FR-020).
#
# Run by the `kafka-init` service in docker-compose.yml, which `make up` invokes once the broker
# reports healthy. It is a one-shot job: it does its work, prints what it did, and exits.
#
# WHY this is a script and not application code: see the TRADEOFF comment on the kafka-init service
# in docker-compose.yml. The short version is that the channels must exist before any service is
# built, so nothing that requires a running Spring application can provision them.

set -euo pipefail

# The INTERNAL listener, not localhost:9092. This script runs inside the Compose network, where
# `localhost` is this container — the broker is a different host entirely. `kafka:29092` is the
# listener the broker advertises to other containers (T029). Getting this wrong produces a
# connection refused that looks like the broker is down when it is perfectly healthy.
BOOTSTRAP="${BOOTSTRAP_SERVER:-kafka:29092}"

# Three partitions, so messages for different orders can be processed concurrently (FR-027). One
# partition would serialise the entire system and silently defeat the load test in build step 9 —
# everything would still work, just without the concurrency it is meant to prove.
PARTITIONS="${TOPIC_PARTITIONS:-3}"

# Forced by the single-broker local setup: a replication factor above 1 needs more brokers to put
# the replicas on, and topic creation fails outright. Not a production topology, and the README
# says so (R6).
REPLICATION="${TOPIC_REPLICATION:-1}"

# The seven message types, mirroring the constants in Topics.java. Kept in the same order as that
# file so the two can be read side by side.
#
# TRADEOFF: this list duplicates Topics.java, and a rename there will not break this script — it
# will create a channel nobody publishes to, which is a silent failure. The alternative was
# generating this list from the compiled jar, rejected because it makes environment startup depend
# on a build having happened, which is exactly the chicken-and-egg this script exists to avoid.
# Scenario 5 in quickstart.md is the guard: it lists the channels and counts fourteen.
TOPICS=(
  order.created
  seats.reserved
  seats.rejected
  payment.succeeded
  payment.failed
  order.confirmed
  order.cancelled
)

echo "Provisioning channels on ${BOOTSTRAP} (partitions=${PARTITIONS}, replication=${REPLICATION})"

create() {
  local topic="$1"
  # --if-not-exists is what makes this idempotent (FR-021). Without it, the second `make up` on an
  # environment whose volumes survived would fail with TopicExistsException, turning a re-run into
  # an error for doing nothing wrong.
  kafka-topics \
    --bootstrap-server "${BOOTSTRAP}" \
    --create --if-not-exists \
    --topic "${topic}" \
    --partitions "${PARTITIONS}" \
    --replication-factor "${REPLICATION}" \
    > /dev/null
  echo "  ${topic}"
}

for topic in "${TOPICS[@]}"; do
  create "${topic}"
  # Every message type gets its own dead-letter channel rather than sharing one (FR-025). A single
  # shared DLT would mix unrelated failures together, so a consumer draining it would have to
  # re-derive what each message was — and a poison message in one flow would sit alongside
  # thousands from another.
  create "${topic}.DLT"
done

# Counting is the actual verification. Every command above could succeed while the broker held
# fewer channels than expected — --if-not-exists reports success for a channel that already exists
# with the WRONG partition count, for instance. This asserts the end state rather than trusting the
# steps that led to it.
expected=$(( ${#TOPICS[@]} * 2 ))
actual=$(kafka-topics --bootstrap-server "${BOOTSTRAP}" --list | grep -c -E '^(order|seats|payment)\.' || true)

if [ "${actual}" -ne "${expected}" ]; then
  echo "FAILED: expected ${expected} channels, found ${actual}" >&2
  exit 1
fi

echo "${expected} channels ready"
