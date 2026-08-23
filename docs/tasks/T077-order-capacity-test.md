# T077 — Specifying overload behaviour, and a HikariCP lesson found along the way

**What this task did:** wrote the test proving that when the connection pool is genuinely full,
excess requests are refused fast with `503`, rather than queuing behind the default timeout — and
hit a real HikariCP limit while designing it, which is worth knowing about.

---

## Why this test needs a real server, not MockMvc

`OrderApiIT` (T076) uses MockMvc — a simulated servlet layer, no real network socket, one thread
handling the call directly. That is fine for checking status codes and headers, but it cannot
exhibit **real connection contention**: there is no concurrent traffic actually competing for the
pool's connections.

Saturating a pool needs a genuinely running server accepting genuinely concurrent connections, so
this test re-declares its own `@SpringBootTest`:

```java
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderCapacityIT extends PostgresIT {
```

`RANDOM_PORT` starts an actual embedded Tomcat on a free port, and the test talks to it with a real
`java.net.http.HttpClient` — this is genuinely going over a socket, the same as a real client would.

## The wrong turn: shrinking the pool

The first version of this test shrank `spring.datasource.hikari.maximum-pool-size` to `1` for the
test class, planning to occupy that single connection and watch the next request get refused.

Two things went wrong, in sequence, and both are worth knowing before you go looking for a
smaller/faster test pool yourself:

**First**, HikariCP enforces a floor: `connectionTimeout cannot be less than 250ms`. An attempt to
also shrink the timeout to `200` for a faster test failed at application startup with exactly that
message.

**Second**, even fixing that and leaving the timeout at the real 250ms default, a pool of size 1 made
the application fail to **start at all** — before any test method ran:

```text
HikariPool-2 - Connection is not available, request timed out after 251ms (total=1, active=1, idle=0, waiting=0)
```

Spring Boot's own startup needs more than one database connection in flight at once — Flyway's
migration check and Hibernate's schema validation can overlap — and a pool of exactly one is too
small for the *application* to come up, let alone for a test to saturate deliberately. This failure
looks exactly like a broken test, and it took inspecting the actual exception chain to see it was a
pool-sizing problem instead.

## The fix: use the real pool, unmodified

Rather than guessing a small-but-safe pool size, this test does not touch the pool size at all. It
reads however large the real, production-configured pool is, and holds **every** connection in it:

```java
int poolSize = ((HikariDataSource) dataSource).getMaximumPoolSize();
List<Connection> held = new ArrayList<>();
for (int i = 0; i < poolSize; i++) {
    held.add(dataSource.getConnection());
}
```

This sidesteps the startup race entirely — the application starts normally against its full,
untouched pool — and it is arguably a *more* faithful test than a shrunk one would have been: it
proves the actual configured pool saturates and refuses correctly, not a special smaller pool that
exists only inside this test.

## What the test proves once it runs against production code

With every connection held, a real HTTP request is sent, and the response is checked:

```java
assertThat(response.statusCode()).isEqualTo(503);
assertThat(response.headers().firstValue("Retry-After")).isPresent();
assertThat(body).contains("capacity");
assertThat(body).doesNotContain("validation-failed");
```

**Confirmed today**: `Tests run: 1, Failures: 1` — an honest `expected: 503, but was: 404`, because
`OrderController` (T083) does not exist yet. That the failure is a clean assertion mismatch rather
than the earlier context-loading crash is itself proof the fix worked: connection holding, server
startup, and the HTTP round trip all function correctly. Only the endpoint is missing, which is
exactly the state this batch of tests is meant to leave the module in.
