# Feature Specification: Seat Holds & the Inventory Authority

**Feature Branch**: `003-inventory-seat-locks`

**Created**: 2026-08-27

**Status**: Draft

**Input**: User description: "Build step 3: inventory-service and the seat locks. Scaffold Web, JPA,
PostgreSQL Driver, Data Redis, Kafka, Flyway, Actuator. Redis key `seat:{showId}:{seatId}` → orderId,
TTL 120s. `DefaultRedisScript<Long>` beans and the calling service method. Consumes OrderCreated →
emits SeatsReserved or SeatsRejected. `processed_events(event_id UUID PRIMARY KEY, consumer_name,
processed_at)`. `@Version` on Reservation, retry once on optimistic lock failure. Known traps: the key
uses showId, not eventId — the contracts renamed that field precisely because it was ambiguous with
message identity, and the brief's original key format predates the rename. This is where the
constitution bites hardest: concurrent reservation is named explicitly as requiring tests that
exercise concurrent execution, so a happy-path test is not sufficient here."

## Clarifications

### Session 2026-08-27

- Q: When this service has decided an outcome but the message channel is momentarily unavailable,
  how does that decision survive? → A: With its own transactional outbox, exactly as the order
  service has. The durable reservation, the note that the message was processed, and the record of
  the announcement to be sent are all written in one atomic unit of work, and a relay sends the
  announcement afterwards. This is the second outbox in the system and is deliberately a copy rather
  than a shared abstraction — the constitution prefers duplication to premature abstraction, and two
  instances is not yet a demonstrated need. It buys three things: an outcome decided against the seat
  state of the moment can never be recomputed later against a world that has moved on, a channel
  outage never blocks consumption or back-pressures unrelated orders sharing a partition, and the
  crash-between-commit-and-announce gap closes by construction rather than by retry.

- Q: Two of the three frozen refusal causes require knowing which seats exist. Where does the seating
  plan come from? → A: Seeded by a versioned migration. This service owns a per-show seating plan
  created at startup by the same migration mechanism that creates its tables, including one show
  whose pool is exactly the ten seats the step-9 load test contends over. No catalogue service exists
  anywhere in the roadmap, so nothing later takes this responsibility away. No administrative
  endpoint is added: this service exposes no HTTP surface beyond health and metrics, and inventing
  one now would raise a token-validation question at step 7 for something that is not part of the
  saga.

- Q: When the fast contention store loses its contents, it reports seats free that durable
  reservations still hold. Which record is authoritative? → A: The durable reservation. The fast store
  is a cache of it, not a second copy of the truth, which is what the constitution's requirement of a
  single authoritative source for shared inventory demands. On startup, every durable reservation
  that is still held and has not yet lapsed is replayed into the fast store before the service begins
  consuming booking requests, so that the window in which the two can disagree is bounded by startup
  rather than by the hold lifetime. Consuming before that replay finishes is what would permit a
  double-booking, so the ordering is a requirement rather than an optimisation.

- Q: When the service cannot reach its stores and therefore cannot decide a booking request at all,
  what happens to that message? → A: The consume fails so that the broker redelivers it, and after a
  bounded number of attempts the message is routed to its dead-letter channel. No outcome is announced
  while the stores are unreachable. This deliberately extends the rule FR-013 already sets for a
  repeated version-check failure: a failure that is not about the seats must never be announced as a
  seat refusal, because none of the three frozen causes means "we could not tell", and answering with
  one of them would permanently cancel an order that would have succeeded. The cost is accepted
  honestly — retrying stalls that partition, which holds up unrelated orders sharing it, and is the
  correct form of back-pressure while a dependency is down. The bounded attempt count is what stops a
  genuinely poisonous message from stalling a partition forever with no operator signal.

- Q: Should the concurrency tests drive contention through the message channel, or by calling the
  reservation operation directly from many threads? → A: Both, at different levels, because neither
  alone is adequate evidence. The exact-count contention assertions are driven by many threads calling
  the reservation operation directly against the real fast store and the real durable store, since the
  channel caps genuinely simultaneous work at its partition count — three, frozen in step 1 — and a
  broken all-or-nothing hold could pass a three-way race by luck rather than by correctness. A
  separate end-to-end test drives a smaller batch through the channel to prove exactly what the direct
  test bypasses: the consumer wiring, the duplicate guard on the delivery path, the recorded
  announcement and its relay, per-order ordering, and the contracts as they appear on the wire.

- Q: When a hold lapses and nothing ever confirms the order, what happens to the durable reservation?
  → A: It moves to an explicit expired state rather than lingering in the held state with a lapse time
  in the past. A lapsed reservation is retired the moment its seats are next contended for, in the
  same unit of work as the new booking, so correctness never depends on a sweeper having run; a
  periodic sweeper exists only as tidy-up for seats nobody asks for again. The reason for the extra
  state is that it makes liveness a property of the row rather than of a time comparison every reader
  must remember to perform — which in turn makes a database-level uniqueness constraint over held
  seats expressible, giving a guarantee against double-booking that sits underneath the fast store and
  survives the fast store being wrong. A time-filtered definition of liveness cannot be expressed as a
  constraint at all.

- Q: Where do the seats used by the large concurrency tests come from — the seeded plan, or fixtures
  the tests create? → A: The tests create their own shows and seat pools. The seeded plan carries only
  what the running system itself needs: the ten-seat show the step-9 load test contends over, plus one
  further show so that per-show scoping of seat labels is exercisable. This resolves a contradiction in
  the criteria as first drafted, which asked for five hundred concurrent requests over disjoint seat
  sets — needing at least five hundred distinct seats — while promising a seeded plan far smaller than
  that. Seeding a large venue instead was rejected because tests sharing one pool interfere unless each
  carefully partitions it, which makes failures order-dependent, and because it would ship data to
  every environment that exists only to be tested against. A test's appetite should not decide what
  data the system carries.

## User Scenarios & Testing *(mandatory)*

This step introduces the service that owns the one resource the whole marketplace competes for: a
seat. Everything before it was bookkeeping — an order is just a row until somebody decides whether
the seats behind it can actually be had. This is the first place in the system where two buyers can
want the same thing and only one can have it.

The step's defining problem is that a seat hold must be **all-or-nothing across several seats at
once**, decided **atomically against every other buyer deciding the same thing at the same
millisecond**. A buyer asking for seats A1, A2 and A3 must end up holding all three or none. A
check-then-act sequence — look at each seat, see it is free, then take it — is wrong here, because
every other contender is doing the same thing in the gap between the look and the take. That gap is
the whole feature.

The second problem is that this service is the first **consumer** in the system. Step 2 established
that messages are delivered at least once and never at most once, and deliberately left duplicate
suppression to whoever consumes them. That bill comes due here.

### User Story 1 - Seats are held for exactly one order, even under contention (Priority: P1)

A booking request arrives naming a show and a set of seats. The service decides, as a single
indivisible act, whether every one of those seats is free. If they all are, it holds them all for
that order and announces that the seats are reserved. If any one of them is not, it holds none and
announces the refusal.

When many buyers ask for overlapping seats at the same instant, exactly one of them wins each seat,
and no seat is ever held for two orders at once — not for a moment, not under any interleaving.

**Why this priority**: This is the entire point of the step and the one property the marketplace
cannot fake. Double-booking is the failure mode a ticket marketplace is judged on: it takes a
customer's money for something that does not exist, and no later step can detect or repair it. Every
other requirement here is in service of this one.

**Independent Test**: Fully testable on its own by driving booking messages onto the channel this
service consumes and reading the outcome messages it produces — no payment service and no order
state machine required. Contention is tested directly: many concurrent requests for one small pool of
seats, asserting that the number of successful holds exactly equals the number of seats available
and that every seat appears in exactly one successful hold.

**Acceptance Scenarios**:

1. **Given** a show whose seats are all free, **When** a booking request for three of them is
   consumed, **Then** all three become held for that order and a seats-reserved announcement is
   produced naming exactly those three seats.
2. **Given** a booking request for three seats where one is already held by a different order,
   **When** it is consumed, **Then** none of the three becomes held for the requesting order, the
   two free seats remain free for anyone else, and a refusal is announced for the whole request.
3. **Given** many orders requesting overlapping seat sets simultaneously, **When** all are consumed,
   **Then** every seat is held by at most one order, the count of seats-reserved announcements plus
   the seats they cover never exceeds the pool, and no order receives both an acceptance and a
   refusal.
4. **Given** many orders requesting seat sets that do not overlap at all, **When** all are consumed,
   **Then** every one of them succeeds, because contention on unrelated seats must not be invented by
   the holding mechanism itself.
5. **Given** a hold has been taken, **When** the announcement is produced, **Then** it carries the
   identity of the durable reservation and the moment the hold lapses, so that a later step can tell
   whether the hold is still valid when it acts on it.
6. **Given** a hold has been taken and nothing further happens, **When** the hold's lifetime elapses,
   **Then** the seats become free for other buyers again without any manual step, so that an
   abandoned saga cannot strand inventory forever.

---

### User Story 2 - A request that cannot be honoured is refused with a stated cause (Priority: P2)

A booking request may name a show this service has never heard of, or seat labels that do not exist
in that show's seating plan, or seats that are all real but currently held by somebody else. Each of
these is refused, and the refusal names which of those three things went wrong — because they mean
entirely different things to whoever reads them. One will succeed if retried in a minute, one never
will, and one points at a mistyped identifier.

**Why this priority**: The refusal path is half of this service's contract and the trigger for the
shortest route through the saga — an immediate cancellation with nothing to compensate. It also
supplies the 990 refusals the step-9 load test asserts on. It sits below story 1 because a refusal
that is merely late or vague costs a buyer an explanation, whereas a wrongly granted hold costs
somebody a seat they paid for.

**Independent Test**: Testable by driving three deliberately unhonourable requests — an unknown show,
a real show with a fabricated seat label, and a request for seats already held — and asserting that
each produces a refusal carrying its own distinct stated cause, and that nothing was held in any of
the three cases.

**Acceptance Scenarios**:

1. **Given** a booking request naming a show that does not exist, **When** it is consumed, **Then** a
   refusal is announced whose stated cause is that the show is unknown, and no seat state changes.
2. **Given** a booking request naming a real show but a seat label absent from its seating plan,
   **When** it is consumed, **Then** a refusal is announced whose stated cause is that the seat does
   not exist, distinct from the cause used when a seat is merely taken.
3. **Given** a booking request whose seats all exist but at least one is currently held, **When** it
   is consumed, **Then** a refusal is announced whose stated cause is contention, and the refusal
   reports back the full set of seats requested rather than only the contended one.
4. **Given** any refusal, **When** the seat state is inspected afterwards, **Then** no seat from the
   refused request is held for the refused order, including seats that were free at the moment of the
   attempt.

---

### User Story 3 - A message delivered twice changes the world once (Priority: P3)

The message channel may deliver the same booking request more than once — after a consumer restart,
after a rebalance, or because the relay that produced it retried a send whose acknowledgement was
lost. The second delivery must not take a second hold, must not create a second reservation, and must
not leave the saga worse off than if it had never arrived.

Crucially, a redelivery must also not *silently swallow the outcome*: if the first delivery was
interrupted before its announcement reached the channel, the redelivery is the system's only remaining
chance to produce that announcement, and skipping it strands the order forever.

**Why this priority**: At-least-once delivery was accepted as the system's semantic in step 2 on the
explicit understanding that consumers would suppress duplicates. This is where that promise is kept.
It is P3 because a duplicate is rare in a healthy system and stories 1 and 2 must exist before there
is anything to duplicate — but it is not optional, because in an unhealthy system duplicates are
exactly what happens.

**Independent Test**: Testable by consuming the identical booking message twice and asserting that
the seat state, the reservation record, and the set of announcements produced are each identical to
what a single delivery produces.

**Acceptance Scenarios**:

1. **Given** a booking request that has already been fully processed, **When** the identical message
   is delivered again, **Then** no second hold is taken, no second reservation is recorded, and the
   seat state is unchanged.
2. **Given** a booking request that has already been fully processed, **When** the identical message
   is delivered again, **Then** the outcome originally decided for it is still the outcome the rest
   of the system sees — a redelivery never converts an acceptance into a refusal because the seats it
   itself is holding now look taken.
3. **Given** a delivery interrupted after the reservation was recorded but before its announcement
   reached the channel, **When** the message is redelivered, **Then** the announcement is produced,
   because an announcement that never arrives stalls the saga with no error anywhere.
4. **Given** two different messages, **When** both are processed, **Then** neither suppresses the
   other, however similar their contents.

---

### Edge Cases

- **Partial availability**: Some requested seats are free and some are not. The hold must be refused
  as a unit and must leave the free ones free. A mechanism that takes what it can and rolls back the
  rest is observable mid-flight by other contenders and is therefore not equivalent.
- **Self-contention on redelivery**: A redelivered request finds its own seats held — by itself. If
  the duplicate guard runs after the hold attempt rather than before it, the request refuses itself,
  and the saga is told its seats are unavailable when in fact they are already its own.
- **Crash between taking the hold and recording it durably**: Seats are held with no durable record
  of who holds them. The hold's own expiry is what frees them; until then those seats are unavailable
  and unattributable.
- **Crash between recording durably and announcing the outcome**: The reservation exists but nothing
  downstream knows. This is the same gap step 2 existed to close, arriving here from the other side,
  and it is answered the same way: the announcement is recorded in the same unit of work as the
  reservation, so after the crash it is simply outstanding work for the relay rather than a decision
  that was computed and lost.
- **Hold lapses while the saga is still in flight**: Payment is slow, the hold expires, another buyer
  takes the seats, and then payment succeeds for the first order. The announcement carries the moment
  the hold lapses precisely so a later step can refuse to act on a stale hold; this step's obligation
  is to state that moment truthfully.
- **Announcement built before it is sent**: If the moment of the announcement and the moment the hold
  lapses are computed at different times, a delayed send can produce an announcement whose hold has
  already expired — which the frozen contract forbids outright, since it requires the lapse to fall
  strictly after the announcement's own timestamp.
- **The hold store loses its contents**: A restart or an eviction of the fast contention store makes
  every held seat appear free while durable reservations still say otherwise. The durable reservation
  is authoritative and the fast store is rebuilt from it before any request is judged — but only if
  the rebuild is ordered *before* consumption begins. A service that starts consuming while the
  rebuild is still running is the double-booking this rule exists to prevent.
- **Seats freed by expiry being rebooked before anything notices**: The fast store frees a seat the
  instant its hold lapses, but the previous reservation is still recorded as live. If the new booking
  cannot retire that stale record itself, the durable constraint rejects a legitimate booking that the
  fast store just granted — the two stores disagreeing in the opposite direction from the usual one.
- **A rebuilt hold outliving what was announced**: A hold restored on startup must expire when the
  original announcement said it would, not a fresh lifetime later. Getting this wrong extends holds
  silently on every restart, and the step-4 fencing check would then trust a hold the rest of the
  system believes has lapsed.
- **A show with no seats, or a request naming seats from a different show**: The seating plan is per
  show, so a seat label valid in one show may be meaningless in another. Validity is judged against
  the named show only.
- **Concurrent updates to one reservation**: Two saga steps attempt to advance the same reservation at
  once. The loser must be detected rather than silently overwritten, and a single retry is the agreed
  response — after which the failure is real and must not be disguised as a seat refusal.
- **A message whose shape is not recognised**: Step 1 requires a consumer meeting an unknown schema
  version to route the message aside rather than process or discard it. That obligation lands on the
  first real consumer, which is this one.
- **A store that is unreachable rather than merely slow**: The request cannot be judged at all. It must
  not be answered with any of the three frozen causes, all of which assert something about the seats
  that has not actually been established. It is redelivered instead, and dead-lettered only after a
  bounded number of attempts, so a transient outage self-heals while a poisonous message still
  surfaces.
- **A transient outage stalling healthy orders**: Redelivering a message holds up the partition it sits
  on, so unrelated orders behind it wait too. This is accepted as honest back-pressure while a
  dependency is down, and is the reason the attempt count is bounded rather than infinite.
- **A refusal that cannot itself be announced**: The channel is unavailable at the moment the outcome
  is produced. The outcome must survive that, or a refused order waits forever for news that was
  computed and then lost.
- **Requests arriving faster than they can be decided**: The step-9 load test drives 1,000 concurrent
  booking attempts at a pool of 10 seats. The overwhelming majority are refusals, and a refusal must
  be cheap — if the cost of losing a race is the same as the cost of winning one, the burst cannot be
  absorbed.

## Requirements *(mandatory)*

### Functional Requirements

**Consuming the booking request**

- **FR-001**: The system MUST consume the order-created message published in step 2 from its frozen
  channel, and MUST NOT require any change to that message's contract.
- **FR-002**: The system MUST treat the show identifier and the seat labels carried by the message as
  the complete description of what is wanted; it MUST NOT consult any other service to interpret them.
- **FR-003**: A message whose declared shape version is not recognised MUST NOT be processed and MUST
  be routed to that message type's dead-letter channel, never discarded, honouring the rule frozen in
  step 1.

**Holding seats**

- **FR-004**: The system MUST decide the availability of every requested seat and take the hold on all
  of them as one indivisible operation, such that no other request can observe a state in which only
  some of them are held.
- **FR-005**: If any requested seat is unavailable, the system MUST hold none of them, and MUST leave
  every seat that was free before the attempt still free after it.
- **FR-006**: A held seat MUST record which order holds it, so that a hold can be attributed and later
  released by its owner rather than by anyone who asks.
- **FR-007**: The identity under which a seat is held MUST be derived from the show identifier and the
  seat label, and MUST NOT incorporate the identity of the message that requested it. A hold keyed by
  message identity is unique per delivery, which would make a redelivered request contend with nothing
  and take a second hold on a seat it already holds.
- **FR-008**: Every hold MUST lapse automatically after a bounded lifetime of 120 seconds without any
  process being responsible for expiring it, so that a saga abandoned by a crash cannot strand
  inventory indefinitely.
- **FR-009**: The system MUST report the moment a hold lapses in its announcement, computed so that it
  is always strictly later than the announcement's own recorded time, as the frozen contract requires.

**The durable reservation**

- **FR-010**: The system MUST record a durable reservation for every successful hold, carrying its own
  identity, the order it belongs to, the show, the seats, its lifecycle state, and the moment the hold
  lapses.
- **FR-011**: The reservation's identity MUST be the one announced to the rest of the system, so that a
  later step can name the reservation it wishes to commit or release.
- **FR-012**: The reservation MUST carry a version that is checked on every update, so that two
  concurrent attempts to advance the same reservation cannot silently overwrite one another.
- **FR-013**: An update losing a version check MUST be retried exactly once. A second failure MUST be
  surfaced as a processing failure and MUST NOT be reported as a seat refusal, because the seats were
  never the problem and a buyer told otherwise would be told something untrue.
- **FR-014**: The durable reservation MUST be the authoritative record of who holds a seat, and the
  fast contention store MUST be treated as a cache of it rather than as a second copy of the truth,
  satisfying the constitution's requirement that shared inventory have a single authoritative source.
- **FR-015**: On startup, the system MUST replay every durable reservation that is still held and has
  not yet lapsed into the fast contention store, and MUST complete that replay before it begins
  consuming booking requests. Consuming first would let a request be judged against a store that has
  forgotten existing holds, which is precisely a double-booking.
- **FR-016**: A hold reconstructed by that replay MUST lapse at the moment its durable reservation
  says it lapses, not a full lifetime after the replay, so that a restart cannot silently extend a
  hold beyond what was announced to the rest of the system.
- **FR-017**: A reservation whose hold has lapsed MUST be moved to an explicit expired state rather
  than remaining held with a lapse time in the past, so that whether a reservation is live is a
  property of the record itself and not a time comparison every reader must remember to perform.
- **FR-018**: A lapsed reservation MUST be retired in the same atomic unit of work as any new booking
  that contends for its seats, so that a newly arriving request never fails because a background
  sweeper has not run yet. Correctness MUST NOT depend on the sweeper's timeliness.
- **FR-019**: A periodic sweeper MUST retire lapsed reservations whose seats nobody contends for
  again, so that expired rows do not accumulate indefinitely as live-looking state.
- **FR-020**: The durable store MUST enforce, as a constraint rather than only in application logic,
  that no two live reservations cover the same seat of the same show. This is the guarantee that
  survives the fast contention store being wrong, and it is the reason the expired state exists: a
  liveness rule defined by a time comparison cannot be expressed as a constraint.
- **FR-021**: A reservation MUST NOT be deleted when its hold lapses. The record of why an order failed
  is what makes a stalled saga diagnosable, and step 5's release path acts on a row that still exists.

**Announcing the outcome**

- **FR-022**: Every consumed booking request MUST produce exactly one outcome announcement: either
  that the seats are reserved, or that they are refused.
- **FR-023**: A refusal MUST state its cause as one of the three frozen causes — the show is unknown,
  a seat does not exist, or the seats are already held — and MUST report the full set of seats
  requested rather than only the ones that were unavailable.
- **FR-024**: An announcement MUST be published under a partition key equal to the order's saga
  identifier, preserving the per-order ordering guarantee established in step 1.
- **FR-025**: An outcome that has been decided MUST be recorded durably in the same atomic unit of
  work as the seat state it was decided against, and sent to the channel only afterwards by a relay,
  so that it can neither be lost if the channel is unavailable nor recomputed later against seat state
  that has moved on.
- **FR-026**: The relay MUST send recorded announcements and mark them sent, retrying those that fail
  and continuing to work while the channel is unavailable. It MUST claim records exclusively so that
  no announcement is sent twice by two concurrent relays, and MUST send the announcements belonging to
  one order in the order they were recorded.
- **FR-027**: A failure to send an announcement MUST NOT prevent this service from consuming further
  booking requests, so that one order's undeliverable outcome does not back-pressure every other order
  sharing its partition.

**Not processing the same message twice**

- **FR-028**: The system MUST record which messages it has already processed, and MUST record that
  fact in the same atomic unit of work as the state change the message caused, so that a message can
  never be marked processed without its effect, nor have its effect without being marked.
- **FR-029**: The record of processed messages MUST be keyed by the message's identity **together with
  the name of the consumer that processed it**, not by message identity alone. Two consumers in this
  service reading the same message are two separate pieces of work, and a key that cannot tell them
  apart silently skips the second one.
- **FR-030**: A message recognised as already processed MUST NOT repeat its effect on seat state or on
  the reservation record.
- **FR-031**: A message recognised as already processed MUST still result in its original outcome being
  visible to the rest of the system. Because the outcome was recorded durably in the same unit of work
  as the effect (FR-025), this is satisfied by the recorded announcement still being outstanding and
  the relay sending it — not by re-deciding the outcome against present seat state.
- **FR-032**: The duplicate check MUST be decided before any hold is attempted, so that a redelivered
  request never contends with the hold it itself already owns.

**The seating plan**

- **FR-033**: The system MUST hold a per-show record of which seat labels exist, because the frozen
  refusal causes require it to distinguish a seat that is taken from a seat that was never real and
  from a show it has never heard of. Without it, two of the three frozen causes are unreachable.
- **FR-034**: The seating plan MUST be created by the same versioned migration mechanism that creates
  the durable records, so that a clean checkout reaches a bookable system with no manual data step.
- **FR-035**: The seeded plan MUST include one show whose seat pool is exactly the ten seats the step-9
  load test contends over, so that the "exactly ten succeed" assertion has a fixed, known target, and
  at least one further show so that per-show scoping of seat labels is exercisable.
- **FR-036**: The seeded plan MUST NOT be enlarged to satisfy the needs of a test. Tests requiring
  larger seat pools MUST create their own shows and seats, so that shipped data reflects what the
  running system needs and tests cannot interfere with one another by sharing a pool.

**Concurrency and correctness evidence**

- **FR-037**: The reservation path MUST have automated tests that exercise genuinely concurrent
  execution, not merely sequential calls, as the project constitution requires by name for ticket
  reservation. A passing happy-path test MUST NOT be accepted as evidence for this path.
- **FR-038**: The concurrency tests MUST assert an exact outcome, not merely the absence of an
  exception: the number of successful holds MUST equal the number of seats available, and every seat
  MUST appear in exactly one successful hold.
- **FR-039**: The tests MUST exercise the real contention mechanism and the real durable store rather
  than substitutes, because the failure being guarded against is a property of those mechanisms and
  disappears when they are replaced.
- **FR-040**: The exact-count contention assertions MUST be driven by many threads invoking the
  reservation operation directly, not through the message channel. The channel caps genuinely
  simultaneous work at its partition count, so a channel-driven test cannot produce enough real
  contention to distinguish a correct all-or-nothing hold from a broken one that happened not to race.
- **FR-041**: Each concurrency test MUST provision its own show and seat pool, sized to the contention
  it intends to create, and MUST NOT depend on the seeded plan or on seats another test also uses. A
  shared pool makes a failure depend on execution order, which is the hardest kind of concurrency bug
  to reproduce and the last kind a test suite should manufacture.
- **FR-042**: A separate end-to-end test MUST drive requests through the message channel to cover what
  direct invocation cannot: the consumer wiring, the duplicate guard on the delivery path, the recorded
  announcement and its relay, per-order ordering, and the contracts as they appear on the wire. Neither
  test alone is sufficient evidence for this path.

**Operations**

- **FR-043**: The service MUST report its own health and expose operational metrics on the same terms
  as every other service, so the environment health check from step 1 continues to cover it.
- **FR-044**: The schema for the durable records MUST be created and evolved by versioned migrations
  applied automatically at startup, so a clean checkout and an existing installation converge without
  manual steps.
- **FR-045**: The system MUST expose, as operational metrics, the number of holds granted, the number
  of refusals broken down by stated cause, the time taken to decide a request, and the age of the
  oldest announcement still awaiting sending, so that a service refusing everything is distinguishable
  from a service refusing nothing, and a stalled relay is visible without reading the store.
- **FR-046**: A refusal MUST cost no more work than an acceptance, so that a burst dominated by losers
  is absorbed at the same rate as one dominated by winners.

**When a decision cannot be made at all**

- **FR-047**: If the system cannot reach the stores it needs to judge a request, it MUST NOT announce
  any outcome for that request. None of the three frozen refusal causes means "the decision could not
  be made", so announcing one would state something untrue and permanently cancel an order that would
  have succeeded.
- **FR-048**: Such a request MUST instead fail its consumption so that the message is redelivered, and
  MUST be retried a bounded number of times before being routed to its dead-letter channel. Unbounded
  retrying is prohibited: it turns a genuinely unprocessable message into a partition stalled forever
  with no operator signal.
- **FR-049**: The system MUST NOT skip, discard, or mark as processed any message it failed to decide,
  so that recovery after the store returns requires no manual replay for the ordinary transient case.
- **FR-050**: The count of messages sent to the dead-letter channel MUST be exposed as an operational
  metric, so that a service failing to decide anything is distinguishable from a service receiving
  nothing.

### Key Entities

- **Show**: A performance that can be booked, identified by the show identifier carried on booking
  messages. Owns a seating plan.
- **Seat label**: The name of one seat within one show's seating plan. Meaningful only relative to its
  show; the same label in two shows denotes two different seats.
- **Seat hold**: A short-lived, automatically expiring claim on one seat by one order. The mechanism by
  which contention is arbitrated. It exists to be fast and to disappear on its own; it is not a record
  of a booking, and it is a cache of the reservation rather than an independent truth.
- **Reservation**: The durable record that an order holds a set of seats, carrying its own identity,
  its lifecycle state, the moment its holds lapse, and a version for concurrency detection. In this
  step two states are reachable: *held*, and *expired* once its holds have lapsed with nothing having
  confirmed the order. The transitions to committed and released arrive with steps 4 and 5. Liveness is
  the state itself rather than a comparison against the clock, which is what allows the durable store
  to constrain two live reservations from covering one seat.
- **Processed message record**: The durable note that a given message has already been handled by a
  given consumer, written in the same unit of work as the effect it describes. It is the sole
  mechanism by which at-least-once delivery is made safe.
- **Outcome announcement**: The seats-reserved or seats-refused message this service produces. Its
  content is decided at the moment the request is judged, and must not be recomputed later against a
  world that has moved on.
- **Recorded announcement**: The durable statement that an outcome announcement must be sent, written
  in the same unit of work as the reservation and the processed-message record. It is this service's
  own outbox — the second in the system, deliberately a copy of the order service's rather than a
  shared abstraction — and it is what makes the decided-but-not-yet-sent state survivable.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-001**: With 1,000 booking requests submitted concurrently against a pool of exactly 10 seats,
  exactly 10 are granted and exactly 990 are refused, with zero seats appearing in more than one
  granted hold — repeatable across at least 20 consecutive runs with no run deviating.
- **SC-002**: Across at least 500 concurrent requests for partially overlapping seat sets, zero
  requests are granted a partial hold: every granted request holds every seat it asked for, and every
  refused request holds none.
- **SC-003**: Across at least 500 concurrent requests for entirely disjoint seat sets, drawn from a
  seat pool the test provisions for itself, 100% are granted — demonstrating that no contention is
  invented by the holding mechanism itself.
- **SC-004**: A booking request is decided — granted or refused — within 150 milliseconds at the 95th
  percentile while 200 requests per second are arriving, matching the rate step 2 established that the
  order service sustains.
- **SC-005**: Every held seat becomes available again within 5 seconds of its 120-second lifetime
  elapsing, with no process having been asked to expire it, measured across at least 100 abandoned
  holds.
- **SC-006**: Delivering the identical booking message 10 times produces exactly one reservation, one
  set of held seats, and one outcome visible to the rest of the system.
- **SC-007**: When the service is killed after a reservation is committed but before its outcome
  reaches the channel, 100% of such outcomes reach the channel within 10 seconds of restart with no
  manual step, and zero orders are left holding a reservation with no announcement.
- **SC-008**: Each of the three refusal causes is produced by the condition it names and by no other,
  verified by one deliberately constructed request per cause.
- **SC-009**: An announcement produced by this service is consumed and interpreted by an independent
  reader into a value equal to the one this service decided, confirming the step-1 contracts are
  honoured on the wire.
- **SC-010**: Every announcement reports a hold-lapse moment strictly later than its own recorded time,
  across 100% of announcements produced under load, including those produced while the channel was
  briefly unavailable.
- **SC-011**: Two concurrent attempts to advance one reservation result in exactly one succeeding and
  the other being detected, retried once, and then either succeeding or failing visibly — with zero
  cases of one silently overwriting the other.
- **SC-012**: A clean checkout reaches a working service with a correct schema and a populated seating
  plan using only the documented startup command, with zero manual database preparation.
- **SC-013**: After the fast contention store is emptied and the service restarted, 100% of durable
  reservations that are still held and unlapsed are observable as holds again before the first booking
  request is judged, with zero seats granted to a second order across at least 50 such restarts.
- **SC-014**: A hold restored by that rebuild lapses within 5 seconds of the moment its original
  announcement stated, never a further full lifetime later, measured across at least 50 restored holds.
- **SC-015**: A booking request produces one connected trace covering the order service's acceptance,
  this service's decision, and the announcement it publishes — with zero requests producing
  disconnected traces.
- **SC-016**: A seat whose hold has lapsed is successfully rebooked by a different order on the first
  attempt, with zero bookings failing because the previous reservation had not yet been retired,
  measured across at least 100 rebookings with the periodic sweeper disabled.
- **SC-017**: Attempting to record two live reservations covering the same seat of the same show is
  rejected by the durable store itself, verified by bypassing the fast contention store entirely — the
  guarantee must hold even when the mechanism above it is wrong.
- **SC-018**: With the stores made unreachable for 30 seconds under continuous load, zero outcome
  announcements are produced for the affected requests, and after the stores return 100% of those
  requests are decided with no manual replay and no message lost.
- **SC-019**: A message that can never be decided reaches the dead-letter channel within its configured
  attempt limit and is visible as a metric, while messages behind it resume being decided once it has
  moved aside.
- **SC-020**: Under a burst of 1,000 concurrent requests dominated by refusals, the 95th-percentile
  decision latency is within 20% of the same measurement under a burst dominated by acceptances,
  demonstrating that losing a race is not more expensive than winning one.

## Assumptions

- **Scope is the reserve path only.** This step consumes the order-created message and produces the
  reserved-or-refused outcome. Releasing a hold when an order is cancelled, and committing a
  reservation when an order is confirmed, both require messages that no service publishes yet — the
  order state machine that emits them arrives in steps 4 and 5. The release mechanism is scaffolded
  here so that step 5 is a body to fill in rather than a new design, but it is neither wired to a
  consumer nor verified in this step.
- **The contention mechanism's core logic is a deliberate developer exercise.** Following the pattern
  established for the outbox relay in step 2, the scripts that perform the atomic all-or-nothing check
  and take are delivered as documented stubs with their contract written out, together with everything
  around them — the wiring, the calling method, the seeding, and the tests that judge them. The step is
  finished once those bodies are implemented, reviewed, and passing, not when they are handed over.
- **The contracts from step 1 are frozen and are not changed here.** The show identifier is `showId`
  and message identity is `messageId`; the original brief's key format predates that rename and is
  superseded by it. The three refusal causes are exactly the three already defined, and this step adds
  none.
- **The processed-message table deviates from the original brief's shape.** The brief specified message
  identity alone as the key with the consumer name as an ordinary column. That cannot distinguish two
  consumers in one service processing the same message, so the key is composite (FR-029). The brief's
  column naming also predates step 1's rename of message identity away from the word "event"; the
  naming is reconciled at plan time rather than carried forward inconsistently.
- **No catalogue service exists and none is built here.** The seating plan is local to this service and
  seeded by migration; no other service in the roadmap owns shows or seats, so nothing later reclaims
  this responsibility. Changing the plan means writing a migration, which is the intended cost — the
  seat pool the load test asserts against should not be editable at runtime by accident. Tests needing
  larger pools build their own rather than growing the seed.
- **The sweeper is tidy-up, not correctness.** Lapsed reservations are retired inline by whatever next
  contends for their seats, so a sweeper that is late, stopped, or absent can never cause a legitimate
  booking to fail. It exists so that seats nobody asks for again do not accumulate as live-looking
  state, and it is deliberately not on any path a buyer waits behind.
- **This service gets its own outbox, copied rather than shared.** It is the second in the system. The
  constitution prefers duplication to premature abstraction and requires an abstraction to be justified
  by demonstrated need; two instances of a pattern is the point at which that need is first *visible*,
  not yet the point at which it is demonstrated. If a third service needs one, extraction should be
  revisited — and the note is recorded here so that decision is made deliberately rather than by
  accumulation.
- **No HTTP API is exposed beyond health and metrics.** Reading availability is the read model's job
  and arrives in step 6; adding a query endpoint here would create a second answer to the same question
  before the intended one exists.
- **The service does not register with a service registry.** No registry exists until step 7, matching
  the decision already recorded for the order service.
- **Money is not touched at this step.** The amount carried on the booking message is passed through
  untouched; nothing here charges, validates, or reasons about it.
- **Holds are not extended or renewed.** A hold lives exactly its configured lifetime from the moment it
  is taken. Whether a saga that outlives its hold can proceed is decided in step 4 by the fencing check,
  using the lapse moment this step reports.
- **Sustained-rate and burst targets match step 2's.** This service sits directly behind the order
  service in the saga, so a rate the front door sustains is the rate this service must absorb; no
  independent capacity target is invented.
- **Announcement retention is out of scope**, matching the decision recorded for the order service's
  outbox in step 2. Sent records are kept indefinitely and a retention policy is a later operational
  concern, recorded as a known gap rather than silently ignored.
