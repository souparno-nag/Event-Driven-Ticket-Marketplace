# T135 — `RedisConfig`

**What this task did:** wrote `RedisConfig.java` — this service's first Redis-facing configuration
class, and the first Redis-facing anything in this entire project. There's no order-service file to
port here; this one is new.

---

## The genuinely interesting question this task raises: does this class even need to exist?

Here's the honest starting point. Spring Boot, the moment `spring-boot-starter-data-redis` is on the
classpath (which it has been since T115), automatically provides a working `StringRedisTemplate` bean
on its own — no configuration class required. It also automatically reads
`spring.data.redis.timeout` (set in T117, to `1s`) and applies it as the underlying Lettuce client's
command timeout when it builds the connection factory. Checked directly by decompiling Spring Boot's
own `RedisAutoConfiguration` and `LettuceConnectionConfiguration` classes rather than assumed: both the
bean and the timeout wiring are already there, for free, with zero code.

So writing a `RedisConfig` class that just declares `new StringRedisTemplate(connectionFactory)` is,
functionally, asking for something Spring Boot was already going to hand over unasked. Given this
project's stated preference for flat, obvious code over ceremony, that's worth being honest about
rather than dressing up as more necessary than it is.

## The actual reason it's worth writing anyway

The command timeout here isn't an ordinary performance tuning knob — it's a direct requirement named by
the project's own constitution. Principle IV forbids an event handler from performing an
unbounded-latency operation inline in its critical processing path. The Redis call this template will
make — evaluating `lock_seats.lua`, on the same thread that's deciding whether a real buyer gets their
seats, synchronously, inside a database transaction — is precisely that operation. If Redis ever goes
quiet rather than crashing outright, something has to notice within a bounded time, or a single slow
dependency stalls every booking request queued behind it.

That requirement being satisfied by a property nobody has to look at is a fragile way for a
constitution-level constraint to be enforced. A future edit to `application.yml` that drops or
mistypes `spring.data.redis.timeout` would silently remove the bound, and nothing in the *code* would
say so — a reader auditing this service's configuration classes for how it meets Principle IV would
find nothing here to point to. Declaring the `StringRedisTemplate` bean explicitly, in a file whose
only job is Redis, gives that requirement a place to live in code: a comment a reader actually finds
by looking at what wires Redis together, rather than something they'd only discover by already knowing
which property to go check.

## What this class deliberately does NOT do

It does not construct its own `RedisConnectionFactory`. That would have been the more "thorough"-looking
choice — building a `LettuceClientConfiguration` by hand, setting `.commandTimeout(...)` explicitly in
Java rather than trusting the property. It was rejected on a concrete risk: a hand-built factory
existing *alongside* the property-driven one Spring Boot would otherwise build creates two places that
claim to control the same setting, and the day someone edits `application.yml`'s timeout expecting it
to take effect — without also remembering a parallel Java-side factory exists — is the day the two
silently disagree, with whichever one wins depending on bean-loading order rather than on which one
anybody actually intended. Building on top of the auto-configured `RedisConnectionFactory`, rather than
replacing it, keeps exactly one thing in charge of the timeout: the property this service already
correctly plumbs through.

---

## Verifying it

Two things were verified against the real stack `make up` provides, both in a combined smoke test with
T134, T136, and T137:

**The template genuinely works.** A value was written through the autowired `StringRedisTemplate` and
read back correctly against the real Redis container — not merely constructed without error, but
proven to actually round-trip a value.

**The timeout is genuinely wired, not merely present in a file.** Rather than trying to trigger a real
timeout under a race (an approach tried first and abandoned as too timing-fragile to trust — see the
commit history), the test reads the *actual* configuration object the connection factory was built
with: `((LettuceConnectionFactory) connectionFactory).getClientConfiguration().getCommandTimeout()`
came back as exactly `Duration.ofSeconds(1)`. That's a direct, deterministic confirmation that
`spring.data.redis.timeout` really did reach the object that will enforce it — a distinction that
matters concretely in this build step, since T117's own correction earlier proved a schema property
that *looked* right in `application.yml` had in fact been silently ignored the whole time. Reading the
file is not the same claim as reading the wired object; only the second one was trusted here.
