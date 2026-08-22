# Specification Quality Checklist: Event Contracts & Local Foundation

**Purpose**: Validate specification completeness and quality before proceeding to planning
**Created**: 2026-08-22
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

- Items marked incomplete require spec updates before `/speckit-clarify` or `/speckit-plan`

### Accepted with qualification: "Written for non-technical stakeholders"

Checked as a reviewer decision on 2026-08-22, not because the spec reads as non-technical prose,
but because this criterion does not apply cleanly to this feature and the reviewer accepted it on
that basis. The reasoning is recorded here so the tick is not mistaken for a claim the spec is
approachable to a lay reader.

This is a developer-facing foundation feature: its deliverables are a shared contract library, a
build root, and a local environment. The audience is genuinely the developer and the downstream
service modules, and the spec says so explicitly in its opening paragraph rather than inventing an
end-user framing that does not exist.

The spec does avoid naming concrete technologies throughout — backing components are described
by role ("message broker", "relational database", "search index") rather than product name — so
the *technology-agnostic* requirement is fully met. What cannot be met is genuine
non-technical readability, because concepts like message envelopes, saga correlation, and schema
versioning are the substance of the feature, not incidental jargon. No wording change fixes that
without making the spec useless for planning.

**Verdict**: accepted by the reviewer; the checklist gate is clear. No further iteration needed on
this item, and it should not be re-raised on later features of the same kind.

### Deliberate additions beyond the original CLAUDE.md sketch

Three defects in the source description were corrected in this spec rather than carried forward.
These are recorded here so the change is traceable during planning:

1. **Identifier collision resolved.** The source used `eventId` for both message identity and the
   concert/show being ticketed, producing a garbled `eventId_ticketed` field on `OrderCreated`.
   Split into a message identifier and a show identifier, with FR-003 and SC-007 enforcing that
   the two remain unambiguous.
2. **Hold-expiry timestamp added to reservation success** (FR-008). The source carried no way for
   a confirming service to detect that its seat hold had expired during a stalled saga, which
   permits a double-booking that the load test would not catch. Adding it now avoids a
   cross-service contract version bump later.
3. **Payment decline rule made exact.** "Amount ends in 7" did not specify whole units or minor
   units. Pinned in Assumptions to the last digit of the minor-unit value, so the rule is
   deterministic for both the demo and the load test.

### Clarification session 2026-08-22

Three ambiguities were resolved and written into the spec. All three were raised now rather than
deferred to planning because each one constrains an artifact this step freezes — the contract
field set, or the set of channels created at startup — and would otherwise force a
cross-service change later.

1. **Trace context placement**: message headers only (FR-024, SC-010). Keeps observability
   concerns out of the seven contracts, so changing how the system is traced never requires a
   contract version bump.
2. **Dead-letter topology**: one dead-letter channel per message type, fourteen channels total
   (FR-020, FR-025, SC-009). Directly changes what this step provisions.
3. **Unrecognized schema version**: dead-letter without processing (FR-023). Rejects the silent
   -skip alternative, which would strand sagas with no diagnostic record.
4. **Partition keying and ordering**: every message keyed by saga identifier across multiple
   partitions (FR-026, FR-027, SC-011). This is a correctness property, not a tuning choice —
   it is what guarantees a payment result is never applied ahead of the reservation result for
   the same order, while still letting unrelated orders process concurrently so the later load
   test exercises real contention.

One question of the five-question budget was left unused. The remaining candidate — seed data
for shows and seats — was deliberately not asked, because seat inventory is owned by the
inventory service and its schema arrives in a later step; deciding it now would constrain a
design that has not been specified yet.

### Constitution alignment

- **Principle II (Testing Standards)**: User Story 1 is verifiable with no running
  infrastructure; the environment stories are verifiable by observed health state. The
  concurrency-focused testing the constitution requires attaches to the seat-locking and
  reservation work in later steps, where shared mutable state first appears.
- **Principle IV (Performance)**: No user-facing latency path exists in this step, so no latency
  budget is set here. Budgets attach from the first request-serving step onward.
- **Principle V (Human-Gated Tooling)**: Reflected in the final assumption — container tooling
  and the language toolchain are documented prerequisites installed by the developer, with steps
  provided rather than commands run automatically.
