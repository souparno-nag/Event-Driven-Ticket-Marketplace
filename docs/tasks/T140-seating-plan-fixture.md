# T140 — `SeatingPlanFixture`

**What this task did:** wrote a small helper that provisions a throwaway show and a seat pool of any
size, for one test's exclusive use — the mechanism `research.md` and the spec's clarification session
both already committed to, without which SC-002 and SC-003's hundreds-of-seats contention tests would
have nowhere to get their seats from.

---

## The contradiction this fixture resolves

Worth restating plainly, because it's the entire reason this file exists: SC-003 needs at least 500
distinct seats to test that disjoint bookings never invent contention with each other. The seeded
plan `V1__create_seating_plan.sql` creates carries about eleven. Those two facts were genuinely in
tension when first drafted, and the spec's own clarification session resolved it by deciding tests
build their own pools rather than the seed growing to satisfy the hungriest test (FR-036, FR-041).
This class is that decision, implemented.

## Why seat rows here, but never through `Show` or `ShowSeat`'s own API

```java
jdbc.update("INSERT INTO shows (show_id, name) VALUES (?, ?)", showId, namePrefix + "-" + showId);
```

Plain SQL, not `entityManager.persist(new Show(...))`. This deserves a specific justification rather
than reading as an implementation shortcut, because `Show` and `ShowSeat` (T125) don't actually offer
a constructor a test *could* call even if it wanted to — both are deliberately read-only from the
application's point of view, with only a protected no-arg constructor JPA itself uses. That narrowness
was a real design decision in T125: nothing in this service's own code ever creates a show or a seat
label at runtime, because the only legitimate way either comes to exist is a migration.

Adding a public, save-capable constructor to either class purely so this fixture could call it would
have widened that surface for a reason that has nothing to do with the reason it was narrowed in the
first place. Inserting via `JdbcTemplate` instead — the exact same mechanism `V1__create_seating_plan.sql`'s
own seed uses — respects that boundary precisely: a test fixture is allowed to do what a migration
does (insert rows directly), not what application code is forbidden from doing (create a show through
its own object model).

## Returning labels, not just an id

```java
public record ProvisionedShow(UUID showId, List<String> seatLabels) { }
```

A test needing, say, three specific overlapping seats and three specific disjoint ones needs to know
what those labels actually are — a caller shouldn't have to memorize or duplicate this fixture's own
internal naming scheme (`seat-0`, `seat-1`, ...) to construct a valid booking request against seats it
just created. Returning the exact list alongside the id means a test can pick `labels.get(0)` and
`labels.get(1)` for an overlapping request and `labels.subList(0, 250)` versus `labels.subList(250, 500)`
for a disjoint one, without caring how the labels are actually spelled.

## One batched insert, not five hundred round trips

`jdbc.batchUpdate(...)` rather than a loop calling `jdbc.update(...)` once per seat. For SC-002 and
SC-003's five-hundred-seat pools specifically, five hundred individual round trips to the database
would make provisioning the fixture itself a meaningful fraction of the test's own running time — for
a setup step neither test is actually trying to measure anything about.

---

## Verifying it

A temporary test (not committed) provisioned a 500-seat show and confirmed: exactly 500 labels came
back, with no duplicates; `SeatingPlanRepository.findExistingSeatLabels` — the application's own,
real repository, not a second query invented for the test — correctly found all 500 when asked about
them; a second, independent call to the fixture produced a different show id with no collision; and,
critically, the seeded "Load Test Hall" show still reported exactly its original ten seats afterward —
proving the fixture genuinely leaves the seeded plan untouched rather than merely being documented as
doing so.
