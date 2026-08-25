# T110: the README stops describing a system that no longer matches reality

The repository README was still written as if build step 1 were the whole story: "no order service,
no seat locking, no payments, no saga." That was true when it was written and stayed true right up
until this build step actually landed `order-service`. Left as it was, the README would have been
actively wrong, not just incomplete — a new reader following it would be told a working HTTP service
doesn't exist.

## What changed, and why each piece

**The status banner.** Now says "build step 2 of 11" instead of "build step 1", and names what
`order-service` actually does: accept a booking, record it durably together with the first saga
event, and read it back. Still explicit about what *isn't* there yet — seat locking, payment,
compensation — because the whole point of this banner is to stop a reader from assuming more than
what's built.

**A new `order-service` section**, following the same pattern the existing `common-events` section
already sets: what the module owns, then something runnable. Three things it covers on purpose:

- **What it owns** — the `Order` aggregate and its transactional outbox, and the one-sentence version
  of why that matters: the order row and the outbox row are written together, so either both exist
  or neither does.
- **Its port** — 8081, stated plainly, since "what port is this thing on" is usually the first
  question anyone actually running the service has.
- **How to submit and read one back** — two runnable `curl` commands, not prose describing the API,
  because a reader trying this out wants something to paste into a terminal, not a description of
  what pasting something into a terminal would do.

**The roadmap table's step 2 row**, flipped from "not started" to done — otherwise the new
`order-service` section above and the roadmap table below it would be contradicting each other
within the same document.

**A short addition to the `Tests` section.** It previously described only `common-events`'s own test
(500 messages, 100 orders, 8 threads) as if that were the entirety of what `./mvnw verify` does. It
no longer is; `order-service` has its own substantial integration suite, and leaving that
undocumented would have made the Tests section quietly incomplete right next to a brand-new section
describing a whole new module.

## What deliberately didn't change

The `Choose what runs` profile table still says `obs` is "needed from build step 8" — that's `infra/.env`'s
job to caveat (see T107), and duplicating that nuance in two places risks the two drifting apart from
each other rather than staying consistent. The specification section at the bottom still only lists
`specs/001-event-contracts-foundation/` — extending it to also list step 2's own specification
documents wasn't part of this task's scope, and is left for whoever writes step 11's final pass over
this file.
