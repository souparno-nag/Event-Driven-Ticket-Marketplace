# Feature Specification: Event Contracts & Local Foundation

**Feature Branch**: `001-event-contracts-foundation`

**Created**: 2026-08-22

**Status**: Draft

**Input**: User description: "Build step 1 of the Event-Driven Ticket Marketplace: the shared event contract library, the multi-module build root, and a one-command local infrastructure environment in which every backing service reports healthy."

## Clarifications

### Session 2026-08-22

- Q: How should distributed trace context travel with each message so a saga can be followed
  end-to-end in the tracing UI? → A: Message headers only — trace context rides in broker
  message headers and never appears in the message body, keeping the seven contracts purely
  about business facts.
- Q: What should happen to a message that repeatedly fails processing, and therefore how many
  channels does this step create? → A: One dead-letter channel per message type — fourteen
  channels in total, so a poisoned message stays typed and identifiable to its saga step.
- Q: When a consumer receives a message whose schema version it does not recognize, what should
  it do? → A: Route it to the dead-letter channel without processing it — fail loudly and
  preserve the message rather than dropping it silently.
- Q: How should messages be distributed across channel partitions, given that a saga's steps
  must be applied to an order in the right order? → A: Key every message by its saga identifier
  across several partitions, giving strict ordering within one order while unrelated orders
  proceed in parallel.

## User Scenarios & Testing *(mandatory)*

The consumers of this feature are the developer building the marketplace and the six services
that will be built on top of it in later steps. This step ships no end-user functionality by
design: its value is that it freezes the contracts and the environment everything else depends
on, so that later steps cannot be blocked or forced into rework by an unstable foundation.

### User Story 1 - Frozen event contracts every service can depend on (Priority: P1)

A developer building any downstream service needs a single, shared definition of every message
that crosses a service boundary. They add one dependency to their module and get the complete
set of saga message types, each with a stable field set, a stable name, and a serialized form
that is identical no matter which service wrote it. When they publish a message from one service
and consume it in another, the object that comes out is equal to the object that went in.

**Why this priority**: Every other module in the project imports these types. A field renamed or
a serialized shape changed after downstream work begins forces edits across six services at
once. Freezing the contracts first is what makes the remaining ten build steps independent.

**Independent Test**: Fully testable in isolation by round-tripping every message type through
serialization and back, asserting equality, without any running infrastructure, database, or
other module present.

**Acceptance Scenarios**:

1. **Given** an instance of any of the seven saga message types populated with representative
   values, **When** it is serialized to its wire form and deserialized back, **Then** the
   resulting object is equal to the original, including nested collections and timestamp
   precision.
2. **Given** a serialized message that contains an additional field the current code does not
   recognize, **When** it is deserialized, **Then** deserialization succeeds and the unknown
   field is ignored rather than raising an error.
3. **Given** any saga message type, **When** its envelope fields are inspected, **Then**
   `messageId`, `sagaId`, `occurredAt`, and `schemaVersion` are all present and populated.
4. **Given** the contract module, **When** its dependency tree is inspected, **Then** it pulls
   in no application-framework dependencies, so that any module can depend on it freely.
5. **Given** two messages describing the same saga step, **When** their `sagaId` values are
   compared, **Then** they are equal, allowing every message in one order's lifecycle to be
   correlated.

---

### User Story 2 - One-command healthy local environment (Priority: P2)

A developer sitting down at a clean checkout runs a single command and, after a bounded wait,
every backing service the marketplace needs is running and reporting itself healthy. They can
see at a glance which components are up, and they can tear the whole environment down and bring
it back to the same state repeatedly without manual cleanup between runs.

**Why this priority**: Steps 2 through 11 each require a working environment before any of their
own work can be verified. Time lost to a flaky or partially-started environment is paid again on
every subsequent step. It sits below the contracts only because contract work can proceed
without it.

**Independent Test**: Fully testable by running the startup command on a machine with no prior
project state and confirming every component reaches a healthy status within the expected
window, then confirming a teardown-and-restart cycle reproduces that same state.

**Acceptance Scenarios**:

1. **Given** a clean checkout with no previously created local state, **When** the developer
   runs the documented startup command, **Then** every backing component reaches a healthy
   status without further manual intervention.
2. **Given** a running environment, **When** the developer queries the status of the
   environment, **Then** each component reports its health individually, so a single unhealthy
   component can be identified without reading raw logs.
3. **Given** a running environment, **When** the developer tears it down and starts it again,
   **Then** it returns to the same healthy state, with no residual state causing startup to
   fail.
4. **Given** a component that depends on another to be ready first, **When** the environment
   starts, **Then** the dependent component does not begin work until its dependency reports
   healthy, rather than failing and relying on restarts.
5. **Given** a developer whose machine already uses one of the required ports, **When** startup
   fails, **Then** the failure names the conflicting component and port rather than failing
   silently.

---

### User Story 3 - Reproducible multi-module build and message channels (Priority: P3)

A developer runs one build command at the repository root and the entire project — the contract
module now, and every service module added later — compiles and tests as a unit, with shared
dependency versions declared in exactly one place. Message channels for each message type exist
as soon as the environment is running, so a downstream service can publish on first run without
a provisioning step.

**Why this priority**: This makes the project additive: later steps register a new module and
inherit the build. Valuable, but the contract types and the environment can both be exercised
before the full build skeleton is finished.

**Independent Test**: Testable by running the root build on a clean checkout and confirming a
successful build of all registered modules, and by listing message channels against a freshly
started environment.

**Acceptance Scenarios**:

1. **Given** a clean checkout, **When** the developer runs the root build command, **Then**
   every registered module compiles and its tests run, in dependency order, in a single
   invocation.
2. **Given** the build configuration, **When** a shared dependency version is inspected,
   **Then** it is declared once at the root and inherited, not repeated per module.
3. **Given** a freshly started environment, **When** the available message channels are listed,
   **Then** fourteen channels exist — one per message type plus one paired dead-letter channel
   per message type — without a manual provisioning step.
4. **Given** the environment is started a second time, **When** channel creation runs again,
   **Then** it succeeds without error despite the channels already existing.

---

### Edge Cases

- **Message identity versus domain identity**: The system carries two distinct identifiers that
  both describe an "event" in everyday language — the unique identity of a single message, and
  the identity of a concert or show being ticketed. These MUST be named distinctly so no code
  path can confuse one for the other.
- **Unknown fields on the wire**: A message written by a newer service reaching an older
  consumer must not break that consumer. Deserialization ignores fields it does not recognize.
- **Schema evolution**: A message shape needs to change after downstream services exist. The
  envelope carries a version so consumers can detect and branch on it rather than failing.
- **Unrecognized schema version**: A consumer meets a version newer than it understands. It
  stops rather than guessing, and the message is preserved on its dead-letter channel where it
  can be inspected and replayed after the consumer is upgraded.
- **Poisoned message blocking a channel**: A message that can never be processed must not stall
  the saga step for every other order. It is moved aside to a dead-letter channel after its
  retries are exhausted, letting the channel drain.
- **Lock expiry during a stalled saga**: Seat holds expire on a timer. If a saga stalls past
  that expiry, the seat may be legitimately taken by someone else, and the original saga must
  not later confirm it. The contracts must carry enough information for a confirming service to
  detect that its hold has expired.
- **Steps of one saga arriving out of order**: A payment result must never be applied to an
  order before the reservation result that preceded it. Keying every message by saga identifier
  confines one order's messages to a single partition, so their relative order is preserved
  without any consumer having to reorder them.
- **Repeated environment startup**: Channel creation, schema setup, and volume initialization
  all run again on every start and must tolerate already-existing state.
- **Slow component startup**: Components that take longest to become ready must not cause a
  false failure verdict; the health wait must accommodate the slowest component.
- **Constrained developer machines**: The full environment is memory-hungry. The expected
  minimum resource footprint must be documented so a developer learns the requirement before a
  confusing out-of-memory failure.

## Requirements *(mandatory)*

### Functional Requirements

**Event contracts**

- **FR-001**: The system MUST provide a single shared module defining all messages exchanged
  between services, depended on by every service module.
- **FR-002**: Every message MUST carry a common envelope of: a unique message identifier, a saga
  correlation identifier equal to the order identifier, an occurrence timestamp, and an integer
  schema version.
- **FR-003**: The message identifier field MUST be named distinctly from the identifier of the
  concert or show being ticketed, so that the two cannot be confused at any call site.
- **FR-004**: The system MUST define exactly seven message types covering order creation, seat
  reservation success, seat reservation rejection, payment success, payment failure, order
  confirmation, and order cancellation.
- **FR-005**: Every message type MUST be immutable once constructed, so a message cannot be
  altered after being published or while being handled concurrently.
- **FR-006**: Messages MUST serialize to a self-describing text format and MUST deserialize back
  to an equal object, preserving timestamp precision and collection contents.
- **FR-007**: Deserialization MUST ignore unrecognized fields rather than failing, so a consumer
  running older contracts tolerates messages from a newer producer.
- **FR-008**: The reservation-success message MUST carry the moment its seat hold expires, so a
  service completing the saga later can determine whether its hold is still valid before
  confirming.
- **FR-009**: Rejection and failure messages MUST each carry a machine-comparable reason, so
  compensating logic can branch on cause rather than parsing prose.
- **FR-010**: The contract module MUST NOT depend on any application framework, so it can be
  consumed by any module and unit tested with no container.
- **FR-023**: A consumer receiving a message whose schema version it does not recognize MUST NOT
  process it, and MUST route it to that message type's dead-letter channel. Silently discarding
  it is prohibited, because these messages move money and seat inventory and a lost one strands
  a saga with no record of why.
- **FR-024**: Trace correlation data MUST travel in broker message headers and MUST NOT be added
  to the message body. The seven contracts stay limited to business facts, so that changing how
  the system is observed never forces a contract version bump.

**Local environment**

- **FR-011**: The system MUST bring up, via a single documented command, the full set of backing
  components: message broker, in-memory store, relational database, search index, service
  registry, tracing collector, and metrics collector.
- **FR-012**: Every component MUST declare a health check that reports readiness to serve, not
  merely that its process has started.
- **FR-013**: Components with ordering dependencies MUST wait for their dependencies to report
  healthy before starting.
- **FR-014**: The message broker MUST run without a separate coordination service.
- **FR-015**: Environment startup MUST be repeatable: a teardown and restart MUST reach the same
  healthy state without manual cleanup.
- **FR-016**: The system MUST provide a single command that reports the health of every
  component, so a developer can identify one unhealthy component without reading raw logs.
- **FR-017**: The documentation MUST state the minimum memory and disk footprint the environment
  requires.

**Build and channels**

- **FR-018**: The system MUST provide a build root that compiles and tests all registered modules
  in dependency order from one command.
- **FR-019**: Shared dependency versions MUST be declared once at the build root and inherited by
  every module.
- **FR-020**: One message channel MUST exist per message type once the environment is running,
  plus one paired dead-letter channel per message type — fourteen channels in total — all
  created without a manual provisioning step.
- **FR-021**: Channel creation MUST be idempotent, succeeding when channels already exist.
- **FR-025**: A message that cannot be processed after its configured retries MUST be routed to
  the dead-letter channel paired with its own message type, so that a failed message remains
  identifiable to the saga step that produced it rather than being pooled with unrelated
  failures.
- **FR-026**: Every message MUST be published under a partition key equal to its saga
  identifier, so that all messages belonging to one order are delivered and processed in the
  order they were produced.
- **FR-027**: Message channels MUST be created with more than one partition, so that messages
  belonging to different orders can be processed concurrently while FR-026 preserves ordering
  within any single order.
- **FR-022**: The build root MUST be structured so a later service module is added by
  registration alone, without restructuring existing modules.

### Key Entities

- **Message envelope**: The fields common to every message — message identifier (unique per
  message, used for duplicate suppression by consumers), saga identifier (equal to the order
  identifier, correlating every message in one order's lifecycle), occurrence time, and schema
  version.
- **OrderCreated**: A buyer has requested seats and the order is awaiting the saga. Carries the
  order, the buyer, the show being ticketed, the requested seats, and the amount owed.
- **SeatsReserved**: The requested seats were held successfully. Carries the order, the seats,
  the reservation identity, and the moment the hold expires.
- **SeatsRejected**: The requested seats could not all be held. Carries the order, the seats, and
  a comparable reason.
- **PaymentSucceeded**: The amount was collected. Carries the order, the payment identity, and
  the amount.
- **PaymentFailed**: The amount was not collected. Carries the order and a comparable reason.
- **OrderConfirmed**: The saga completed successfully. Carries the order and the confirmed seats.
- **OrderCancelled**: The saga was compensated. Carries the order and a comparable reason.
- **Show**: The concert or performance being ticketed, identified distinctly from any message
  identifier. Referenced by messages; its own storage arrives in a later step.
- **Seat**: A single sellable position within a show, identified by a stable label unique within
  that show.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: A developer starting from a clean checkout reaches a fully healthy local
  environment using a single documented command, with no manual steps beyond that command.
- **SC-002**: Every backing component reports healthy within 5 minutes of starting the
  environment on a machine meeting the documented minimum resources.
- **SC-003**: 100% of message types round-trip through serialization with an exactly equal
  object returned, verified automatically.
- **SC-004**: The full project builds and its tests pass from a single root command on a clean
  checkout, with zero manual dependency installation beyond the documented prerequisites.
- **SC-005**: Ten consecutive teardown-and-restart cycles all reach a fully healthy state,
  demonstrating the environment is reproducible rather than incidentally working.
- **SC-006**: A message serialized by one module and deserialized by another yields an equal
  object, verified without both modules sharing an in-memory instance.
- **SC-007**: No identifier in the contract module can be read as ambiguous between message
  identity and show identity, confirmed by review of every field name.
- **SC-008**: Adding a new empty module to the build requires changing only the build root's
  module registration and the new module's own descriptor.
- **SC-009**: A freshly started environment exposes exactly fourteen message channels, verified
  by listing them, with no manual provisioning performed.
- **SC-010**: No message type carries any trace-correlation field in its body, confirmed by
  review of every contract field, so observability changes cannot force a contract version bump.
- **SC-011**: Messages published for a single order are observed by a consumer in exactly the
  order they were produced, across at least 100 orders processed concurrently, with zero
  out-of-order deliveries within any one order.

## Assumptions

- **Scope is the foundation only.** No service logic, no persistence schemas beyond what the
  environment needs to start, and no user-facing endpoints ship in this step. Those arrive in
  steps 2 onward.
- **The consumer is the developer and downstream modules.** This step deliberately delivers no
  end-user value; it is judged on whether it makes the remaining steps independent.
- **Contracts are frozen here and versioned thereafter.** After this step, changing a message
  shape is a versioned change with a compatibility story, not an edit.
- **A hold-expiry timestamp is carried on reservation success even though nothing consumes it
  yet.** This is deliberate: adding it later would require versioning the contract across
  services that already exist. It exists so the confirming service in a later step can detect a
  stale hold rather than confirming a seat someone else now holds.
- **Seats are identified by a stable label unique within a show** (for example a row-and-number
  label), not by a global identifier, since seat numbering is meaningful only within a show.
- **Monetary amounts carry exactly two decimal places** and use a decimal representation rather
  than a binary floating-point one, so that comparisons in the simulated payment rule and in the
  load test are exact.
- **The simulated payment decline rule is defined precisely** as: payment declines when the last
  digit of the amount expressed in minor units is 7. This removes the ambiguity in an informal
  "amount ends in 7" phrasing, which does not say whether it refers to whole units or minor
  units.
- **Only the seven listed message types exist at this stage.** Later steps may add message types;
  they may not silently change these.
- **Local single-machine environment only.** Cluster deployment is a later step and is out of
  scope here.
- **Required tooling is installed by the developer, not by the build.** Container tooling and the
  language toolchain are documented prerequisites; per project governance, installation steps are
  provided to the developer rather than performed automatically.
