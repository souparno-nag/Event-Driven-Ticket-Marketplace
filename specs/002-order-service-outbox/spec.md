# Feature Specification: Order Acceptance & the Transactional Outbox

**Feature Branch**: `002-order-service-outbox`

**Created**: 2026-08-23

**Status**: Draft

**Input**: User description: "Build step 2: order-service and the transactional outbox"

## Clarifications

### Session 2026-08-23

- Q: Does this step deliver a relay that actually sends messages, or a scaffolded relay whose
  send logic the developer writes by hand? → A: Scaffolded, deliberately. Everything around the
  relay ships complete and working — the schema, the atomic write, the relay's schedule, its class
  and method signatures, and the tests that will judge it — but the poll-and-publish method body is
  left as a documented stub, accompanied by written instructions describing its contract, for the
  developer to implement. The developer's implementation is then reviewed: kept if it passes the
  tests and reads well, replaced otherwise. Step 2 is therefore complete only once that method is
  filled in, and the criteria that depend on messages actually reaching the channel are verified at
  that point rather than at hand-over.

- Q: When the relay publishes an outbox message minutes after the buyer's request finished, should
  that message still belong to the same trace as the original request? → A: Yes. The trace context
  active when the outbox record is written is stored on the record itself, as its own field
  alongside the serialized message rather than inside it, and the relay restores that context and
  attaches it to the outgoing message's headers. One trace therefore spans the buyer's request, the
  later publication, and every consumer downstream.

- Q: If one outbox record fails to publish repeatedly, should the relay retry it forever or give up
  and set it aside? → A: Give up. Each record counts its failed send attempts and stores the reason
  the last one failed; after a configured limit the relay stops retrying it and marks it parked.
  Because records for one order must publish in the order they were recorded, a parked record halts
  that order's remaining records — which is correct, since that saga now needs a human — while every
  other order continues unaffected. The parked count and the age of the oldest unsent record are
  exposed as metrics so the condition is visible rather than silent.

- Q: How many booking requests per second must the service accept without blowing its latency
  budget? → A: 200 accepted requests per second sustained, absorbing the full 1,000-request burst
  that the step-9 load test produces within the existing acceptance latency budget, with the relay
  draining the outbox at least as fast as requests arrive so the backlog returns to empty rather
  than growing. A sustained rate of 500 per second is recorded as a stretch target for later work:
  it is not this step's bar, but no design decision here may preclude reaching it.

- Q: When more requests arrive than the service can handle, should the extra requests queue and
  wait, or be turned away quickly? → A: Turned away quickly. The number of requests being recorded
  at once is bounded and the recording step carries its own timeout; anything beyond that bound is
  refused immediately with a distinct "busy, retry later" response, counted under its own metric so
  overload is visible and never mistaken for a malformed request. This follows the rule already set
  for an unreachable store: a fast, honest refusal beats an acknowledgement the system cannot
  honour.

## User Scenarios & Testing *(mandatory)*

This step introduces the first service that owns state. It is the front door of the marketplace:
the place a buyer's booking request is accepted, given an identity, and recorded durably. It is
also the origin of every saga — the first message in an order's lifecycle is produced here.

The step's defining problem is that two things must happen together and cannot be made atomic by
ordinary means: the order must be written to the service's own store, and the rest of the system
must be told the order exists. Those are two different systems. A failure between them either
loses an order the buyer was told was accepted, or announces an order that does not exist. This
feature exists to make that gap impossible to observe.

### User Story 1 - A booking request is accepted durably, or not at all (Priority: P1)

A buyer submits a request to book specific seats for a show. The service records the order in a
pending state, records — in the very same atomic unit of work — the fact that the rest of the
system must be notified, and immediately returns an acknowledgement carrying the order's
identity. The buyer is not made to wait while seats are held or money is taken; they are told
only that their request has been accepted for processing.

If anything at all goes wrong while recording, the buyer is told the request was not accepted, and
no trace of a half-created order or an orphaned notification remains.

**Why this priority**: Everything downstream begins here. An order that exists without its
notification is a saga that never starts and a buyer whose seats are never held; a notification
without an order is a saga that reserves seats for something that does not exist. Neither can be
repaired by any later step, so this atomicity is the single most valuable thing the step ships.

**Independent Test**: Fully testable on its own by submitting a booking request against a running
service with a real store, then inspecting the store directly to confirm exactly one order record
and exactly one unpublished notification record exist and refer to the same order. Failure
injection during the write confirms neither record survives.

**Acceptance Scenarios**:

1. **Given** a running service and a valid booking request, **When** the buyer submits it,
   **Then** the response acknowledges acceptance for processing, carries a unique order
   identifier, and does not report a final booking outcome.
2. **Given** a valid booking request, **When** it has been accepted, **Then** the store contains
   exactly one order in the pending state and exactly one unpublished notification record
   describing that order's creation, and both refer to the same order identifier.
3. **Given** a booking request being recorded, **When** the write of the notification record
   fails, **Then** the order record does not persist either, and the buyer receives a failure
   response rather than an acknowledgement.
4. **Given** a booking request whose seat list is empty, contains duplicates, or whose amount is
   negative or carries the wrong number of decimal places, **When** it is submitted, **Then** it
   is rejected with a response naming the offending field, and nothing is recorded.
5. **Given** the service cannot reach its store, **When** a buyer submits a request, **Then** the
   request is refused outright rather than acknowledged, because an acknowledgement the system
   cannot honour is worse than a visible failure.
6. **Given** many buyers submitting simultaneously, **When** their requests are accepted, **Then**
   every accepted order has its own distinct identifier and its own notification record, with no
   interleaving causing a shared or missing record.
7. **Given** more requests in flight than the service is configured to record at once, **When** a
   further request arrives, **Then** it is refused immediately with a response that says the service
   is busy and the request may be retried, distinct from the response given to a malformed request,
   and nothing is recorded for it.
8. **Given** the store has become slow rather than unreachable, **When** recording exceeds its
   allowed time, **Then** the attempt is abandoned and the buyer is refused, rather than the request
   being held open indefinitely.

---

### User Story 2 - Recorded intent reliably reaches the rest of the system (Priority: P2)

A relay running inside the service continuously looks for notification records that have not yet
been sent, sends each one to the message channel for its message type, and marks it sent. A record
is never sent before the order it describes is durably committed, and a record is never abandoned
because the service crashed at an inconvenient moment. When the service restarts after a crash,
anything unsent is picked up and sent without a developer intervening.

Because sending and marking-as-sent are themselves two systems, the relay may occasionally send
the same notification twice. It never sends none.

**Why this priority**: Without the relay, step 1's message channels stay empty and step 3 has
nothing to consume, so the saga cannot begin. It sits below story 1 because the correctness that
cannot be recovered later lives in story 1: a lost notification record can be re-sent by a fixed
relay, but an order that was never atomically recorded is gone.

**Independent Test**: Testable by inserting unsent notification records directly into the store,
starting the service, and observing the corresponding messages appear on the correct channel and
the records become marked as sent — with no booking request involved.

**Acceptance Scenarios**:

1. **Given** an unsent notification record in the store, **When** the relay next runs, **Then** the
   corresponding message appears on the channel for its message type and the record is marked
   sent.
2. **Given** a notification record already marked sent, **When** the relay runs repeatedly,
   **Then** it is not sent again.
3. **Given** the service is stopped between recording a notification and sending it, **When** the
   service is started again, **Then** the pending notification is sent without any manual step.
4. **Given** several unsent notification records belonging to the same order, **When** the relay
   sends them, **Then** they reach the channel in the order they were recorded.
5. **Given** more than one instance of the service running against the same store, **When** their
   relays run at the same time, **Then** no notification record is claimed by two instances, and
   an instance that finds all records claimed proceeds without waiting on the other.
6. **Given** the message channel is unavailable, **When** the relay attempts to send, **Then** the
   affected records remain unsent and are retried on a later run, and the service continues to
   accept new booking requests.
7. **Given** the send succeeds but marking the record sent fails, **When** the relay runs again,
   **Then** the message is sent a second time, and this duplicate is acceptable because every
   message carries a stable identity that consumers use to suppress it.
8. **Given** a message published by the relay, **When** its partition key is inspected, **Then** it
   equals the order's saga identifier, preserving the per-order ordering guarantee established in
   the previous step.

---

### User Story 3 - The current state of an order can be inspected (Priority: P3)

Anyone holding an order identifier — the buyer who submitted it, or the developer verifying the
system — can ask the service for that order's current state and receive the order's identity, the
buyer, the show, the seats requested, the amount, and where the order currently sits in its
lifecycle. An identifier that does not correspond to any order is reported as not found rather
than as an empty order.

**Why this priority**: This is what makes the step demonstrable and what every later step uses to
confirm the saga advanced. It is last because it reads state that stories 1 and 2 create; without
them it has nothing to show.

**Independent Test**: Testable by accepting one booking request, then reading the order back by its
returned identifier and comparing every field against what was submitted.

**Acceptance Scenarios**:

1. **Given** an accepted order, **When** it is looked up by its identifier, **Then** its state is
   reported as pending along with the buyer, show, seats, and amount exactly as submitted.
2. **Given** an identifier that matches no order, **When** it is looked up, **Then** the response
   reports that no such order exists.
3. **Given** an identifier that is not a well-formed order identifier, **When** it is looked up,
   **Then** the response reports a malformed request rather than a server error.

---

### Edge Cases

- **Crash between commit and send**: The service dies after the order and its notification record
  are committed but before the message is sent. The record is still unsent, so the relay sends it
  after restart. This is the failure the whole feature exists to survive.
- **Crash between send and mark-as-sent**: The message is on the channel but the record still looks
  unsent, so it is sent again. The system is at-least-once by design; duplicate suppression is the
  consumer's responsibility and uses the message identity frozen in step 1.
- **Two relays racing**: More than one service instance polls the same store. Records must be
  claimed exclusively, and an instance must skip records another instance already holds instead of
  blocking behind them, or the relays serialise into a single-threaded queue.
- **Ordering within one order**: An order may accumulate more than one notification record over the
  saga's life. If the relay sends them out of recording order, a consumer could see a later fact
  before an earlier one, defeating step 1's ordering guarantee.
- **A record that can never be sent**: A record whose send fails every time — a malformed payload,
  a channel that no longer exists — would otherwise be retried forever. It is parked after a set
  number of attempts, which stops the wasted work and makes the failure visible, at the cost of
  deliberately stalling that one order until someone looks at it.
- **Burst beyond capacity**: More buyers arrive at once than the service can record. Rather than
  admitting all of them and letting every request slow down until it times out, the excess is
  refused immediately, so that the requests which are accepted stay inside their latency budget and
  the refusals are readable rather than appearing as a wall of timeouts.
- **Backlog growth**: If the channel is unavailable for a long period, unsent records accumulate.
  The relay must claim a bounded batch per run rather than attempting the whole backlog, so that a
  long outage does not turn into a single enormous transaction.
- **Records never cleaned up**: Sent records remain forever and the table grows without bound.
  Retention is acknowledged and deliberately deferred, not overlooked.
- **Impatient buyer submits twice**: The same buyer requests the same seats twice in quick
  succession. Two distinct orders are created and both enter the saga; the seat-holding step
  arbitrates and one is rejected. The order service does not deduplicate.
- **Seats or show that do not exist**: The order service has no catalogue and cannot know. It
  accepts the request on its face; the service that owns seat inventory is the authority and
  rejects unknown seats in a later step.
- **Concurrent modification of one order**: Two saga steps attempt to advance the same order at
  once. One must be detected and rejected rather than silently overwriting the other's state.
- **Outbox record written with no active trace**: A record created by something other than a
  buyer's request — a future scheduled process, or a record inserted by hand during testing — has no
  trace context to store. The relay sends it anyway, untraced, rather than treating the missing
  context as a failure.
- **Clock skew on the recorded timestamp**: The recorded occurrence time is the service's own
  clock. It is used for human diagnosis and ordering-by-recording-sequence, never as the
  authoritative ordering mechanism, which is the record's own monotonic sequence.

## Requirements *(mandatory)*

### Functional Requirements

**Accepting a booking**

- **FR-001**: The system MUST expose an endpoint that accepts a booking request consisting of the
  buyer, the show, the requested seats, and the amount owed.
- **FR-002**: The system MUST respond to an accepted request with an acknowledgement that the
  request has been accepted for later processing, carrying the newly assigned order identifier,
  and MUST NOT imply the booking has succeeded.
- **FR-003**: The system MUST assign every order a unique identifier that also serves as that
  order's saga correlation identifier, matching the correlation rule frozen in the previous step.
- **FR-004**: The system MUST record a new order in a pending state, meaning accepted but not yet
  reserved, paid, confirmed, or cancelled.
- **FR-005**: The system MUST validate a booking request before recording anything, rejecting an
  empty or duplicate-containing seat list, a missing buyer or show, and an amount that is negative
  or not expressed to exactly two decimal places, and MUST name the offending field in the
  rejection.
- **FR-006**: The system MUST refuse a request it cannot durably record, rather than acknowledging
  it optimistically.

**The outbox**

- **FR-007**: The system MUST write the order record and a record of the notification to be sent in
  a single atomic unit of work, such that either both are durably present or neither is.
- **FR-008**: Each notification record MUST carry the identity of the order it concerns, the type
  of message to be sent, the complete message content in its serialized form, the time it was
  recorded, and a marker of whether and when it has been sent.
- **FR-009**: The system MUST NOT send any message as part of accepting a request; sending is
  performed only by the relay, after the recording transaction has committed.
- **FR-010**: The message content stored in a notification record MUST be the exact serialized form
  that will be sent, so that what a consumer receives is decided at recording time and cannot drift
  if the code changes before the relay runs.
- **FR-025**: Each notification record MUST also store the trace context that was active when it was
  recorded. This MUST be stored as a field of the record, separate from the serialized message, so
  that the message content itself stays limited to business facts as frozen in the previous step,
  and a change to how the system is traced can never force a contract version bump.
- **FR-028**: Each notification record MUST carry a count of how many times sending it has been
  attempted and the reason the most recent attempt failed, so that a record which can never be sent
  is identifiable from the record itself rather than only from logs.

**The relay**

- **FR-011**: The system MUST run a background relay that repeatedly finds notification records
  that have not yet been sent, sends each to the message channel corresponding to its message type,
  and marks it sent.
- **FR-012**: The relay MUST claim the records it is working on exclusively, so that no record is
  sent by two concurrent relays.
- **FR-013**: A relay encountering records already claimed by another relay MUST skip them and
  proceed with unclaimed ones, rather than waiting for the claim to be released.
- **FR-014**: The relay MUST process records belonging to the same order in the order they were
  recorded.
- **FR-015**: The relay MUST claim a bounded number of records per run, so that a large backlog is
  drained across several runs rather than in one unbounded unit of work.
- **FR-016**: A record whose send fails MUST remain unsent and MUST be retried on a subsequent run;
  a failing send MUST NOT prevent the service from continuing to accept new booking requests.
- **FR-017**: The system MUST guarantee that every recorded notification is sent at least once, and
  MUST NOT attempt to guarantee it is sent at most once.
- **FR-018**: Every message the relay sends MUST be published under a partition key equal to the
  order's saga identifier, preserving the per-order ordering guarantee established in the previous
  step.
- **FR-019**: The relay MUST resume automatically on service restart, sending anything left unsent,
  with no manual intervention.
- **FR-026**: When sending a record, the relay MUST restore the trace context stored on that record
  and attach it to the outgoing message's headers, so that the publication is recorded as a
  continuation of the request that caused it rather than as an unrelated trace.
- **FR-027**: A record carrying no stored trace context MUST still be sent, without a trace context
  header and without error, so that a record written outside a traced request is never stranded.
- **FR-029**: After a configured number of consecutive failed send attempts, the relay MUST stop
  retrying a record, mark it as parked, and retain the reason it failed. It MUST NOT delete the
  record and MUST NOT retry it again without human intervention.
- **FR-030**: A parked record MUST halt the sending of later records belonging to the same order,
  because sending past it would break the per-order ordering guarantee. Records belonging to every
  other order MUST continue to be sent unaffected.
- **FR-031**: The system MUST expose, as operational metrics, the number of parked records and the
  age of the oldest record still awaiting sending, so that a stalled order or a growing backlog is
  visible without reading the store directly.

**Reading an order**

- **FR-020**: The system MUST expose an endpoint that returns the current state of a single order
  by its identifier, including buyer, show, seats, amount, and lifecycle state.
- **FR-021**: A lookup for an unknown identifier MUST report that no such order exists, distinctly
  from reporting a malformed identifier or a server failure.

**Concurrency and consistency**

- **FR-022**: The order record MUST carry a version that is checked on every update, so that two
  concurrent attempts to advance the same order cannot silently overwrite one another; the losing
  attempt MUST be detected.
- **FR-023**: The store schema MUST be created and evolved by versioned migrations applied
  automatically at startup, so that a clean checkout and an existing installation converge on the
  same schema without manual steps.
- **FR-024**: The service MUST report its own health and expose operational metrics on the same
  terms as every other service in the system, so the environment health check introduced in the
  previous step continues to cover it.

**Capacity**

- **FR-032**: The system MUST sustain at least 200 accepted booking requests per second while still
  meeting its acceptance latency budget, and MUST absorb a burst of 1,000 concurrent submissions —
  the volume the step-9 load test produces — without failing requests for reasons of capacity.
- **FR-033**: The relay MUST drain outbox records at least as fast as new ones are recorded at the
  sustained rate above, so that the backlog returns to empty after a burst rather than growing
  without bound.
- **FR-034**: No design decision taken in this step may preclude later reaching a sustained rate of
  500 accepted requests per second. That higher rate is explicitly out of scope to achieve or verify
  here, but is recorded so that a choice which caps throughput permanently is recognised as a
  regression rather than a neutral simplification.
- **FR-035**: The number of booking requests being recorded concurrently MUST be bounded, and the
  recording step MUST carry its own time limit, so that a slow store degrades into fast refusals
  rather than into an unbounded queue of requests all timing out together.
- **FR-036**: A request refused because the service is at capacity MUST receive a response
  distinguishable from both a malformed request and a server fault, MUST indicate that retrying is
  appropriate, and MUST be counted under its own metric so that overload is visible as overload.

### Key Entities

- **Order**: A buyer's request to purchase specific seats for a show, and the aggregate the saga
  advances. Holds its own identifier (which is also the saga identifier), the buyer, the show, the
  requested seats, the amount owed, its lifecycle state, a version for concurrency detection, and
  creation and update times. In this step the only reachable state is pending; later steps
  introduce the transitions out of it.
- **Order state**: Where an order sits in its lifecycle. This step establishes pending as the entry
  state and reserves the confirmed and cancelled terminal states for later steps.
- **Outbox record**: A durable statement that a message must be sent, written in the same
  transaction as the state change that caused it. Holds its own monotonic sequence, the order it
  concerns, the message type, the serialized message, the trace context active when it was
  recorded, the time recorded, the time sent, the number of send attempts made, and the reason the
  last attempt failed — where an absent send time is precisely what marks it as outstanding work,
  and an exhausted attempt count marks it as parked and awaiting a human. The trace context is a field of the record, deliberately kept outside the
  serialized message so the frozen contracts stay free of observability concerns.

## Success Criteria *(mandatory)*

### Measurable Outcomes

SC-004 through SC-007 depend on the relay's poll-and-publish method, which ships as a documented
stub for the developer to implement (see Clarifications). They are verified once that method is in
place, not at hand-over. Every other criterion is verifiable against what is delivered.

- **SC-001**: Across 1,000 concurrently submitted booking requests, the number of recorded orders
  and the number of corresponding notification records are equal, with zero orders lacking a
  notification and zero notifications lacking an order.
- **SC-002**: In 100 injected-failure runs where the recording transaction is interrupted, zero
  partial results remain: no run leaves an order without its notification record or a notification
  record without its order.
- **SC-003**: A buyer receives an acknowledgement of an accepted booking request in under 300
  milliseconds at the 95th percentile under a load of 200 concurrent submissions, because
  acceptance performs no seat holding, no payment, and no waiting on the message channel.
- **SC-004**: A recorded notification reaches its message channel within 2 seconds of the
  recording transaction committing, under normal operation.
- **SC-005**: After the service is forcibly killed between recording and sending, 100% of
  outstanding notifications are sent within 10 seconds of restart, with no manual step.
- **SC-006**: With three service instances relaying against one store, every notification is sent
  at least once and no notification is sent by more than one instance in the same run, measured
  over at least 1,000 records.
- **SC-007**: Messages belonging to a single order are observed on the channel in exactly the order
  they were recorded, across at least 100 orders relayed concurrently, with zero inversions within
  any one order.
- **SC-008**: A message produced by this service is consumed and deserialized by an independent
  reader into an object equal to the one recorded, confirming the step-1 contracts are honoured on
  the wire.
- **SC-009**: 100% of invalid booking requests are rejected before anything is recorded, verified
  by confirming the store is unchanged after each rejection.
- **SC-010**: An order accepted through the API can be read back by its identifier with every
  submitted field returned unchanged.
- **SC-011**: A clean checkout reaches a working service with a correct schema using only the
  documented startup command, with zero manual database preparation.
- **SC-012**: A single booking request produces one connected trace in the tracing UI covering both
  the acceptance of the request and the later publication of its message, with zero orders producing
  two disconnected traces.
- **SC-013**: With one permanently unsendable record present alongside at least 100 healthy orders,
  the unsendable record is parked within its configured attempt limit, every healthy order's
  messages are still delivered, and the parked record is visible as a metric.
- **SC-014**: The service sustains at least 200 accepted booking requests per second while the 95th
  percentile acceptance latency stays within its budget, measured over a run of at least 60 seconds.
- **SC-015**: A burst of 1,000 concurrent submissions is fully accepted with zero capacity-related
  failures, and the outbox backlog it creates returns to empty within 30 seconds of the burst
  ending.
- **SC-016**: Under a load deliberately exceeding the sustained target, every request that is
  accepted still meets its latency budget, and every refused request returns a capacity refusal in
  under 100 milliseconds, with zero refusals reported as malformed requests or server faults.

## Assumptions

- **Scope is one service and its outbox.** No seat holding, no payment, no confirmation or
  cancellation logic ships here. The only reachable order state is pending, and the only message
  produced is the order-created message. Steps 3 through 5 supply the rest of the saga.
- **The contracts and the environment are already frozen.** This step consumes the shared contract
  module and the message channels created in step 1 and changes neither. The order-created message
  is published as-is, under its existing channel name and partition key rule.
- **The relay's core method is a deliberate developer exercise.** Everything around it ships
  working: the schema, the atomic write, the relay's schedule and signatures, and the tests that
  judge it. The poll-and-publish body itself is left as a documented stub with written instructions
  describing its contract, because it is the one piece of this step whose reasoning — claiming
  records exclusively, skipping records another relay already holds, and marking a record sent only
  after its send succeeded — is worth working out by hand rather than reading. The step is not
  finished when it is handed over; it is finished once that method is implemented, reviewed, and
  passing.
- **At-least-once delivery is the accepted semantic.** Exactly-once delivery across a store and a
  message channel is not achievable without distributed transactions, which are deliberately not
  used. Duplicate suppression is the consumer's job and relies on the message identity frozen in
  step 1; the consumer-side guard is built in step 3.
- **No catalogue of shows or seats exists yet.** The order service accepts whatever show and seat
  labels it is given. The authority on whether a seat exists and is free is the inventory service,
  built in step 3, which rejects what it cannot honour.
- **No authentication is enforced at this step.** The buyer identifier is supplied in the request
  body and taken on trust. Token validation arrives with the gateway in step 7, and the endpoint
  paths are chosen now so that routing them later requires no change here.
- **The service does not register with a service registry.** No registry exists until step 7;
  registration is added to every service in one pass at that point rather than sitting dormant and
  misconfigured for five steps.
- **Outbox retention is out of scope.** Sent records are kept indefinitely in this step. A
  retention or archival policy is a later operational concern and is recorded as a known gap rather
  than silently ignored.
- **A single relay instance is the expected local configuration**, but the exclusive-claim
  requirement is specified and tested anyway, because the load test in step 9 and the cluster
  deployment in step 10 both introduce more than one instance.
- **Duplicate booking submissions are not deduplicated.** No client-supplied idempotency key is
  accepted at this step; two identical submissions create two orders and the seat-holding step
  arbitrates between them. Adding request-level idempotency later does not require changing the
  contracts.
- **Money is handled exactly as frozen in step 1**: a decimal representation with exactly two
  decimal places, never a binary floating-point one, so the simulated payment rule and the load
  test compare exactly.
