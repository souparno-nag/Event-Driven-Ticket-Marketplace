# T107: a table that was accidentally telling you to wait

`infra/.env` already had a table explaining what each `COMPOSE_PROFILES` value starts and, roughly,
when a given build step first needs it. The `obs` row said "build step 8" — technically true, in the
sense that step 8 (Resilience4j) is where tracing becomes a *formal, planned* piece of work. But it
quietly implied something false: that nothing before step 8 needs Zipkin at all.

That's not quite right. This very build step — step 2, order-service — has its own success
criterion, SC-012, whose entire content is "does an accepting request and the outbox relay's later
publish of that same order's message show up as ONE connected trace, or two unrelated ones?"
Answering that question means actually looking at a trace somewhere, and Zipkin (started by the
`obs` profile) is the only place this project has to look. Someone following this step's own
quickstart, reading only the `.env` table, could reasonably run `make up` with the default `core`
profile, get to scenario S6, and find nothing running on port 9411 — not because anything is broken,
but because the table they were reading told them Zipkin wasn't relevant yet.

The fix is a short note under the table, not a change to the table's numbers themselves (the `full`
and `core` rows are still accurate as written): `core,obs` is what step 2's own S6 scenario needs,
even though the table's own "Needed from" column still correctly says step 8 is where tracing
becomes a first-class, dedicated deliverable rather than one scenario among several.
