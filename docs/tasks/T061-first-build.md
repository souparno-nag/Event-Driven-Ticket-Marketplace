# T061 — Verifying the module joins the build

**What this task did:** ran `./mvnw -pl order-service -am verify` and confirmed the whole project
builds and tests as one unit, with `order-service` in it.

---

## What the command says

```bash
./mvnw -pl order-service -am verify
```

| Part | Meaning |
|---|---|
| `./mvnw` | The Maven wrapper at the repository root — everyone gets the same Maven version |
| `-pl order-service` | **p**roject **l**ist: build this module |
| `-am` | **a**lso **m**ake: and anything it depends on |
| `verify` | Compile, run unit tests, run integration tests, and fail if any of them fail |

`-am` is the part worth understanding. `order-service` depends on `common-events`, so Maven builds
that first — from source, in this same run, not from a previously published copy. Without `-am`,
Maven would go looking for a `common-events` jar in your local repository, not find one, and stop.
(That happened during this task and looked briefly like a real failure.)

## Why `verify` and not `test`

Maven runs through a fixed sequence of phases, and naming one runs every phase up to it:

```text
compile → test → package → verify → install
             ↑                ↑
      unit tests      integration tests
```

This project deliberately separates the two kinds of test, using a naming convention:

- A class ending in **`Test`** is a **unit test**. Fast, no external anything.
- A class ending in **`IT`** is an **integration test**. Starts real infrastructure in Docker.

`test` runs only the first kind. `verify` runs both. Since one of the guarantees this project makes
is about how Kafka orders messages, and that is a property of Kafka rather than of any code here,
proving it needs a real broker — so `verify` is the command that actually checks what matters.

---

## What the run reported

```text
ticket-marketplace ................................. SUCCESS
common-events ...................................... SUCCESS
order-service ...................................... SUCCESS
BUILD SUCCESS
```

- **34 unit tests** in `common-events` — message round-trips, validation rules, channel naming.
- **5 integration tests** in `OrderingGuaranteeIT` — started a real Kafka container, published
  messages, and confirmed each order's messages arrived in the order they were sent.
- **0 tests** in `order-service`. It has no code yet beyond a `main` method.

That last line is the point of this task rather than a shortfall. Phase 1 was only ever about
getting the module into the build; the tests arrive in Phase 3, and they are written *before* the
code they describe.

---

## What this proves

**The dependency rewrite in T058 was correct.** Every renamed library resolved against Spring Boot
3.3.13 and the module compiled. A wrong artifact name would have failed here.

**Test configuration is inherited.** `order-service` was not told how to run tests. The root
`pom.xml` configures the unit/integration split once, and every module gets it. When Phase 3 adds
files ending in `IT`, they will be picked up with no further configuration.

**Containers are cleaned up.** After the run, `docker ps` showed nothing left behind. Testcontainers
starts a small watchdog container called Ryuk whose only job is to remove the containers a test
created, even if the test crashes. Without something like it, a few failed runs leave a machine
full of orphaned brokers.

---

## Phase 1 is complete

The module exists, sits in the build, has its configuration, and compiles alongside everything else.

Nothing yet talks to a database or a broker. Phase 2 adds the schema — the `orders` and `outbox`
tables and the Java classes that map to them — which is the first point at which this service has
anything of its own to test.
