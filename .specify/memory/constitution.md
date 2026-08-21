<!--
Sync Impact Report
===================
Version change: 1.0.0 → 1.1.0
Rationale: MINOR bump — added a new, materially significant Core Principle
governing when an AI coding assistant must defer tooling/environment/credential
actions to the human developer instead of performing them autonomously. No
existing principle was redefined or removed.

Modified principles: n/a (no renames or redefinitions)

Added sections:
- Core Principles: V. Human-Gated Tooling & Environment Changes

Removed sections: n/a

Templates requiring updates:
- .specify/templates/plan-template.md — ⚠ pending manual check: Constitution
  Check section should be reviewed against all five principles (including the
  new Principle V) next time /speckit-plan runs for a feature.
- .specify/templates/spec-template.md — ✅ no constitution-specific references
- .specify/templates/tasks-template.md — ✅ no constitution-specific references
- .specify/templates/checklist-template.md — ✅ no constitution-specific references

Follow-up TODOs:
- TODO(TECH_STACK): No implementation code or dependency manifests exist yet in
  this repository, so principles are written technology-agnostic. Revisit
  Architecture & Technology Constraints once the concrete stack (message
  broker, backend framework, frontend framework) is chosen, likely during the
  first /speckit-plan run. (Carried over from v1.0.0, still unresolved.)
-->

# Event-Driven Ticket Marketplace Constitution

## Core Principles

### I. Code Quality
All code merged into a shared branch MUST pass automated linting and static
analysis, and MUST be reviewed by at least one person other than the author
before merge. No direct pushes to the main branch are permitted. Every module,
service, and event handler MUST have a single, clearly named responsibility;
duplication is preferred over premature or speculative abstraction, and
abstractions MUST be justified by an existing, demonstrated need rather than a
hypothetical future one. Public interfaces (APIs, event schemas, shared
modules) MUST be documented at the point of definition, not in a separate,
easily-stale document.
Rationale: In an event-driven system, components are loosely coupled and often
owned or touched by different contributors over time; consistent, reviewed,
low-complexity code is what keeps that coupling manageable and keeps defects
from propagating silently across service boundaries.

### II. Testing Standards
Every unit of business logic MUST have automated unit tests, and every event
producer/consumer pair MUST have an integration test that exercises the
published event contract, not just the handler in isolation. Flows involving
concurrent state changes — most importantly ticket reservation, purchase, and
inventory decrement — MUST have tests that specifically exercise concurrent or
out-of-order execution, since these are the scenarios most likely to cause
real-world defects (double-booking, oversold inventory) in a ticket
marketplace. Tests MUST fail before the corresponding fix or feature is
implemented and pass afterward; a change that cannot demonstrate this is not
considered verified. CI MUST run the full automated test suite on every pull
request, and a failing suite MUST block merge.
Rationale: Event-driven, concurrent flows fail in ways that manual testing and
casual review reliably miss (race conditions, replayed or out-of-order
events, duplicate delivery); automated tests targeted at those failure modes
are the only reliable safety net.

### III. UX Consistency
User-facing surfaces (buyer flows, seller/organizer flows, checkout, and any
admin tooling) MUST share a single set of interaction patterns, components,
and terminology rather than each screen inventing its own. Every
asynchronous or event-driven UI update (e.g., ticket availability changing,
order status changing) MUST have a defined and consistent loading, success,
and error/failure state — a user MUST never be left looking at a screen that
gives no indication of what happened to their action. Interfaces MUST meet
WCAG 2.1 AA accessibility standards at minimum. Any deviation from an
established pattern MUST be justified in the relevant spec or plan, not
introduced silently during implementation.
Rationale: A ticket marketplace is trust-sensitive — buyers are spending money
against inventory that can change state at any moment due to other users'
actions. Inconsistent or ambiguous feedback erodes trust and causes support
burden and lost sales far more than in a typical CRUD application.

### IV. Performance
Critical user-facing paths — search, event/listing browse, and checkout —
MUST have an explicit latency budget defined in the relevant feature's plan,
and that budget MUST be validated with load testing before a feature that
touches those paths ships. Event handlers and consumers MUST NOT perform
blocking, unbounded-latency operations (e.g., synchronous third-party calls
without a timeout) inline in the critical event-processing path; such work
MUST be offloaded or made asynchronous with a bounded timeout. Any feature
expected to be exposed to a high-demand spike (e.g., a popular on-sale event)
MUST include a load/soak test scenario simulating that spike before release.
Rationale: Ticket on-sales are inherently bursty — demand can spike far above
steady-state within seconds — and an event-driven backend that cannot absorb
and process that burst within budget directly translates into failed
purchases and reputational damage.

### V. Human-Gated Tooling & Environment Changes
An AI coding assistant working in this repository MUST NOT autonomously
install a new tool, dependency, or runtime (e.g., Docker, a JDK/Maven via
start.spring.io, a package manager, a CLI); MUST NOT autonomously obtain,
generate, or configure credentials or secrets (API keys, tokens, service
accounts); and MUST NOT autonomously run shell commands that provision or
change environment/infrastructure state that did not already exist (e.g.,
`docker run`, `brew install`, cloud CLI provisioning commands). Instead, the
assistant MUST stop, briefly explain what is needed and why, and give the
user clear, step-by-step instructions they can run themselves. This
principle applies only to first-time installation, provisioning, and
credentialing actions; it does NOT apply to invoking a tool that is already
installed and configured (e.g., running `mvn test` once Maven is already on
the system, or `docker compose up` once Docker and the compose file already
exist).
Rationale: Installing tools, provisioning infrastructure, and creating or
using credentials have effects that reach beyond the repository — system
state, account access, billing, and secrets — and are hard to review or
reverse from within a code diff. The user MUST retain full visibility into,
and control over, what gets installed on their machine and what credentials
get created or used on their behalf.

## Architecture & Technology Constraints

The concrete technology stack (message broker, backend framework, frontend
framework, datastores) is not yet fixed as of this version, since the
repository currently contains no implementation code. Once chosen, the stack
MUST be recorded here or in a linked architecture document, and the following
constraints apply regardless of the specific technology selected:
- State-changing operations that affect shared inventory (e.g., ticket
  availability) MUST go through a single authoritative source of truth;
  events are a notification/propagation mechanism, not a substitute for
  authoritative state.
- Event schemas MUST be versioned, and a breaking change to an existing event
  schema MUST NOT be deployed without a compatibility plan (dual-publish,
  consumer migration, or equivalent).
- Secrets and credentials MUST NOT be committed to the repository; this
  applies to configuration for message brokers, databases, and any
  third-party integration.

## Development Workflow & Quality Gates

- Every feature MUST go through the spec → plan → tasks → implement workflow
  before code is written, except for trivial fixes (typos, dependency bumps,
  non-behavioral refactors) which may be handled directly.
- Pull requests MUST link the spec or issue they implement and MUST pass
  linting, the automated test suite, and at least one human review before
  merge.
- A pull request that introduces a new event type, changes an existing event
  schema, or changes a checkout/reservation flow MUST call out that fact
  explicitly in its description so reviewers apply the relevant Testing
  Standards and Performance checks above.

## Governance

This constitution supersedes any conflicting team convention or prior
informal practice. Amendments are made by editing this file via the
`/speckit-constitution` workflow (or an equivalent reviewed pull request),
and MUST include an updated Sync Impact Report describing what changed and
why. Versioning follows semantic versioning: MAJOR for backward-incompatible
principle removals or redefinitions, MINOR for new principles or materially
expanded guidance, PATCH for wording/clarification fixes that do not change
meaning. Every pull request and every `/speckit-plan` run MUST verify its
approach against the Core Principles above; unjustified deviation is grounds
for requesting changes in review. Complexity that conflicts with a principle
(e.g., an added abstraction, a skipped test category) MUST be explicitly
justified in the plan or PR description rather than silently introduced.

**Version**: 1.1.0 | **Ratified**: 2026-08-22 | **Last Amended**: 2026-08-22
