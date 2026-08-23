# T072 — Proving the schema and the entities agree

**What this task did:** wrote `SchemaIT` — the first test in this module to run against a real
database — and used it to close Phase 2.

---

## The drift this prevents

Two things in this service describe the same tables, and nothing forces them to match:

| Where | What it says |
|---|---|
| `db/migration/V1__*.sql`, `V2__*.sql` | The SQL that creates the tables |
| `Order.java`, `OutboxRecord.java` | Annotations claiming which table and columns each field maps to |

Rename a column in the SQL and forget the annotation and **everything still compiles**. Java has no
idea the database exists. The mistake surfaces at the first query, at runtime, possibly in
production.

## The test that looks like it asserts nothing

```java
@Test
void hibernateValidatesEveryMappingAgainstTheSchema() {
    assertThat(entityManagerFactory.getMetamodel().getEntities())
            .extracting(entityType -> entityType.getJavaType().getName())
            .contains(Order.class.getName(), OutboxRecord.class.getName());
}
```

That assertion is not the point. **The assertion already happened, during startup.**

`application.yml` sets `ddl-auto: validate`. So when the Spring context was built, Hibernate compared
every entity mapping against the real tables Flyway had just created, and would have refused to build
the `EntityManagerFactory` on any mismatch — a missing column, a wrong type, a misspelled table name.
The context would have failed, and this test method would never have been reached.

Reaching the line **is** the result. What the assertion adds is a statement of *which* mappings were
covered — because a validated schema proves nothing about an entity Hibernate never knew existed.

## Asking Flyway's own records

```java
List<String> applied = jdbc.queryForList(
        "SELECT script FROM flyway_schema_history WHERE success = true ORDER BY installed_rank",
        String.class);

assertThat(applied).containsExactly("V1__create_orders.sql", "V2__create_outbox.sql");
```

Flyway keeps a bookkeeping table recording what it has run. Querying it is a stronger question than
"do the tables exist?", because it proves the migrations **ran, in order, and were recorded** — and
that recording is what makes the next startup skip them rather than try again and fail.

---

## What the run showed

```text
Database: jdbc:postgresql://localhost:32841/test (PostgreSQL 16.15)
Migrating schema "public" to version "1 - create orders"
Migrating schema "public" to version "2 - create outbox"
Successfully applied 2 migrations to schema "public", now at version v2

Tests run: 2, Failures: 0, Errors: 0  -- in com.marketplace.orders.SchemaIT
BUILD SUCCESS
```

Note the port: **32841**, not 5432. Testcontainers deliberately binds a random free port so a test
run never collides with a PostgreSQL the developer already has running.

Everything the previous ten tasks produced is confirmed by this: the SQL is valid PostgreSQL, both
files parse and execute, the `jsonb` column and the partial indexes are accepted, and every entity
annotation matches the column it claims.

---

## Phase 2 is complete

The schema exists, the Java classes that map to it exist, and they have been shown to agree against a
real PostgreSQL rather than assumed to.

Nothing yet accepts an order. Phase 3 begins User Story 1 — and begins it with tests, written and
failing, before the code they describe.
