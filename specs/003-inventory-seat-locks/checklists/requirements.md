# Specification Quality Checklist: Seat Holds & the Inventory Authority

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-08-27
**Feature**: [spec.md](../spec.md)

## Content Quality

- [x] No implementation details (languages, frameworks, APIs)
- [x] Focused on user value and business needs
- [x] Written for non-technical stakeholders
- [x] All mandatory sections completed

## Requirement Completeness

- [x] No [NEEDS CLARIFICATION] markers remain
- [x] Requirements are testable and unambiguous
- [x] Success criteria are measurable
- [x] Success criteria are technology-agnostic (no implementation details)
- [x] All acceptance scenarios are defined
- [x] Edge cases are identified
- [x] Scope is clearly bounded
- [x] Dependencies and assumptions identified

## Feature Readiness

- [x] All functional requirements have clear acceptance criteria
- [x] User scenarios cover primary flows
- [x] Feature meets measurable outcomes defined in Success Criteria
- [x] No implementation details leak into specification

## Constitution Alignment (project-specific)

- [x] Principle II — concurrent execution is required by name (FR-037 through FR-042) and asserted
      with exact outcomes rather than absence-of-exception (SC-001, SC-002, SC-003, SC-011), which is
      what the constitution demands for ticket reservation specifically. The clarification session
      further established that the exact-count assertions must be driven by direct multi-threaded
      invocation, because the channel caps genuine concurrency at its partition count
- [x] Principle IV — the reservation path carries an explicit latency budget (SC-004) and a burst
      scenario matching the step-9 load test (SC-001, SC-020)
- [x] Principle V — module scaffolding is recorded as the developer's action, not the assistant's
- [x] Architecture constraint — shared inventory has a single authoritative source: the durable
      reservation, with the fast contention store defined as a cache of it and rebuilt from it before
      consumption begins (FR-014, FR-015, FR-016, SC-013, SC-014). Reinforced by a database-level
      constraint on live reservations (FR-020, SC-017) that holds even when the fast store is wrong
- [x] Architecture constraint — event schemas are unchanged; this step consumes and produces only
      contracts frozen in step 1 (FR-001, FR-023)
- [x] Principle I — no failure is reported as something it is not: an undecidable request is never
      dressed up as a seat refusal (FR-013, FR-047, FR-048), and the dead-letter count is exposed so
      the condition is visible rather than silent (FR-050)

## Validation Notes

**Iteration 1 (2026-08-27)** — three [NEEDS CLARIFICATION] markers raised, all on decisions with no
obviously-correct default:

- `FR-014` — which store is authoritative when the fast contention store and the durable store
  disagree. Not a detail: the constitution's Architecture & Technology Constraints section requires
  shared inventory to have one authoritative source.
- `FR-018` — whether a decided outcome is protected by a transactional outbox or by direct
  publication plus redelivery.
- `FR-025` — how shows and seating plans come to exist. Two of the three frozen refusal causes are
  unreachable without a seating plan, making this a correctness gate rather than seed-data
  convenience.

**Iteration 2 (2026-08-27)** — all three resolved and recorded in the spec's Clarifications section:
own transactional outbox; seating plan seeded by versioned migration; durable reservation
authoritative with a startup rebuild of the fast store.

**Iteration 3 (2026-08-27, `/speckit-clarify`)** — four further ambiguities found and resolved. All
were Partial rather than Missing, and one exposed an outright contradiction in the spec's own
criteria:

- **Undecidable requests.** Nothing said what happens when the stores are unreachable, and none of the
  three frozen refusal causes means "we could not tell". Resolved as redelivery with a bounded attempt
  count before dead-lettering (FR-047 through FR-050, SC-018, SC-019).
- **Where concurrency is exercised.** The criteria demanded genuine contention but the channel caps it
  at three, so a channel-only test would have been weak evidence dressed as strong. Resolved as direct
  multi-threaded invocation for the exact-count assertions plus an end-to-end test for the wiring
  (FR-040, FR-042).
- **Lapsed reservations.** Only *held* was reachable, leaving expired holds indistinguishable from live
  ones except by a time comparison, which cannot be expressed as a database constraint. Resolved with
  an explicit expired state, inline retirement, and a uniqueness constraint over live reservations
  (FR-017 through FR-022, SC-016, SC-017).
- **Test seat pools.** SC-003 required 500 disjoint seats while the seeded plan promised roughly
  eleven — a direct contradiction. Resolved by having tests provision their own pools (FR-036, FR-041).

All checklist items pass. Spec is ready for `/speckit-plan`.

## Notes for the plan

Two things deliberately left out of the spec because they are implementation shape, not requirements,
but which must not be lost:

- **The processed-message table's naming.** The original brief's `processed_events(event_id UUID
  PRIMARY KEY, ...)` conflicts with step 1's rename of message identity away from the word "event"
  (step 1 FR-003), and its single-column key conflicts with FR-024. Both are reconciled at plan time.
- **The hold key format.** `seat:{showId}:{seatId}` — the brief's original `{eventId}` predates the
  step-1 rename and denotes the message, not the show. FR-007 states the rule; the plan states the
  literal format.
- **The live-reservation constraint's shape.** FR-020 requires the durable store to reject two live
  reservations covering one seat. Expressing this needs seats on their own rows carrying the show, and
  a partial uniqueness constraint scoped to the held state — the constraint cannot be written against
  a lapse-time comparison, which is the whole reason FR-017's expired state exists.
- **Ordering of the startup rebuild against consumer start.** FR-015 requires the rebuild to finish
  before consumption begins. This is a lifecycle-ordering concern, and getting it wrong is silent: the
  service looks healthy and double-books.

## Noted for a later step, not this one

The `SeatsReserved` contract carries no amount, yet payment-service consumes it and must charge one.
Step 4 will need to decide where that amount comes from — re-reading the order, consuming
`OrderCreated` in parallel, or something else. Recorded here so it is met as a decision rather than as
a surprise; it changes nothing in step 3.
