# T179 — `SeatLockRebuilder`

**What this did:** wrote the class that runs once, at the very start of this service's life, to put
Redis back into a state that matches what PostgreSQL already knows — and only afterward lets this
service start actually processing booking requests.

---

## Why Redis needs to be rebuilt from scratch on every restart

This service's Redis is deliberately configured to remember nothing across a restart —
`infra/docker-compose.yml` turns its snapshotting off entirely. That's a real, deliberate choice: Redis
here is a fast cache for "is this seat claimed right now," not the durable record of what actually
happened — PostgreSQL is that record. But it means every restart genuinely erases Redis's memory of
every seat currently on hold, even holds that are still perfectly valid. Left alone, the very first
booking request after any restart would be judged against a Redis that has forgotten every existing
hold — which looks, from the outside, exactly like every held seat suddenly becoming free. Nothing
would even report an error; it would just quietly double-book.

## Why the timing of this class matters as much as what it does

It isn't enough for this class to eventually restore Redis — it has to finish BEFORE this service
starts accepting any real booking request. If even one request were judged while the rebuild was still
in progress, that one request could be granted a seat Redis simply hadn't remembered was already taken
yet — the exact double-booking this whole class exists to prevent, just delayed by a few moments
instead of avoided. That's why the service is deliberately configured not to start listening for
messages at all until this class has explicitly said "go" — the gap between "the application is ready"
and "the application is safe to use" is closed by this one class, on purpose.

## Why an absolute expiry, not a fresh one

When this class restores a hold's key in Redis, it sets it to expire at the EXACT moment the original
hold was always going to expire — not a fresh 120 seconds counted from right now. This matters more
than it might look: without it, every restart would quietly hand every currently-held seat a brand new
full lifetime, no matter how much of its original hold had already passed. A seat that was 10 seconds
from freeing up would instead survive another two full minutes, and anything elsewhere in the system
that already trusts the ORIGINAL expiry — the value already announced on `seats.reserved` — would end
up disagreeing with what Redis now believes.

## Why this runs once, right at startup, and not on some other schedule

Spring Boot has a specific, narrow moment for exactly this kind of "run this once, after everything
else is ready, but before the application starts doing its real job" work — the point this class hooks
into. Two other moments were considered and rejected: running earlier risks the database connection not
actually being ready yet in every possible startup ordering; running later (after the application is
already fully up) would mean the service had already started accepting messages before this class ever
got a chance to run — precisely backwards from what's needed here.

## A real bug this class's own first verification run caught — in the test, not in this class

The first attempt to verify this class against `SeatLockRebuildIT` (T170) genuinely restarted its
"second application" against the REAL local PostgreSQL, Redis, and Kafka running on this machine's
normal ports, instead of the test's own disposable containers — confirmed directly by a log line
showing that second application's Kafka consumer joining a group against `localhost:9092`. The cause:
the test handed its override values to Spring Boot's application builder as ordinary configured
properties, which sit at the LOWEST priority Spring Boot recognises — below `application.yml`'s own
committed, real-environment defaults, which quietly won instead. Passing the same values as
command-line-style arguments fixed it, since those sit at the HIGHEST priority instead. Recorded here
because it is exactly the kind of mistake that would otherwise let a test silently exercise production
infrastructure by accident rather than the isolated environment it was written to use.

## Verifying it

`SeatLockRebuildIT` (T170) is the test built specifically to prove this class works: plant a still-live
hold, wipe Redis entirely (the exact thing a real restart does), start a real second application
pointed at the same database and cache, and confirm the hold is back — at its original, not a fresh,
expiry — the moment that startup finishes, with a competing request for the same seat genuinely
refused. That test failed before this class existed and passes now: `Tests run: 1, Failures: 0,
Errors: 0`.
