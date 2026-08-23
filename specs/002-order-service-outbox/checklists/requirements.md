# Specification Quality Checklist: Order Acceptance & the Transactional Outbox

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-08-23
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

## Notes

### Iteration 1 — 2026-08-23

Two items failed, and both traced to the same unresolved question.

**"No [NEEDS CLARIFICATION] markers remain"** — one marker stood, in the Assumptions section.
`CLAUDE.md` asks for the outbox relay's poll-and-publish method to be scaffolded and left as a
documented stub for the developer to write by hand, while the same document's step-2 verification
requires the event to actually appear on the `order.created` channel. Those two instructions
describe different deliverables, and the spec could not commit to success criteria until one was
chosen.

**"Feature meets measurable outcomes defined in Success Criteria"** — SC-004 through SC-007 all
assert that messages reach the channel, so this item could not be ticked while the scope question
was open.

No other item required a spec edit. Terminology (message, channel, saga identifier, partition key,
show) was aligned with the frozen vocabulary of `001-event-contracts-foundation` so the two specs
read as one system.

### Iteration 2 — 2026-08-23

The scope question was resolved in the developer's favour: the relay ships scaffolded, with its
poll-and-publish body left as a documented stub and written instructions describing the contract,
and the developer implements it. Recorded in a new Clarifications section and in the corresponding
Assumptions entry.

The four messaging criteria were kept rather than weakened, because they remain the right test of
whether the step works; a note under Measurable Outcomes now states plainly that they are verified
once the developer's implementation lands rather than at hand-over. Both previously failing items
now pass, and all 16 checklist items are green.

One consequence worth carrying into planning: step 3 consumes the messages this relay produces, so
it cannot be verified end to end until the stubbed method is written. Either the stub is filled in
before step 3 begins, or step 3's early verification uses messages placed on the channel by hand.
