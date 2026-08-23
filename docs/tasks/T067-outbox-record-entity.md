# T067 — The `OutboxRecord` entity

**What this task did:** wrote the Java class mapping to the `outbox` table, with the three methods
the relay will use to move a record through its lifecycle.

---

## Mapping `jsonb`

```java
@JdbcTypeCode(SqlTypes.JSON)
@Column(nullable = false, columnDefinition = "jsonb")
private String payload;
```

The column is PostgreSQL's `jsonb`; the field is a plain `String`. Hibernate does not connect those
two on its own — without `@JdbcTypeCode(SqlTypes.JSON)` it sends the value as `varchar` and
PostgreSQL rejects it with a type error.

The field stays a `String` on purpose. This class must never parse the payload. The message was
serialized when the row was written, and that is precisely the guarantee the outbox makes: what a
consumer receives was decided at recording time. Parsing and re-emitting it here would quietly
reopen the gap the design closes.

---

## The identifier, and a caveat worth carrying

```java
@Id
@GeneratedValue(strategy = GenerationType.IDENTITY)
private Long id;
```

Unlike `Order`, this id comes from the database. A **monotonic number** is wanted here rather than a
UUID, because this id *defines an order* — the sequence in which messages were recorded, which is the
sequence they must be sent in.

There is a subtlety documented in the class, and it is the kind of thing that otherwise gets
discovered at 2am:

> Identity values are handed out when a row is **inserted**. Rows become visible when their
> transaction **commits**. Those are not the same moment.

Two transactions inserting at once can be assigned ids 10 and 11, and the one holding 11 can commit
first. A relay looking for "the lowest unsent id" would see 11 alone, publish it, and only later see
10 appear — out of order.

This system is not exposed to it: an order advances one saga step at a time, each step driven by
exactly one consumed message, so two records for the same order are never written concurrently. The
caveat is written down anyway, so that a future step which *does* introduce concurrent writes for one
order meets it as a known cost rather than as an intermittent bug that only shows up under load.

---

## No `equals`, and that is the decision

`Order` implements `equals` and `hashCode` on its id. This class deliberately does not, and the
difference is instructive.

`Order`'s id is set in its constructor and never changes, so hashing on it is stable. `OutboxRecord`'s
id is `null` until the row is inserted. If `equals` compared ids:

- Every unsaved record would be **equal to every other unsaved record** — they all have `null`.
- A record's hash code would **change during save**, so anything holding it in a `HashSet` would
  fail to find it afterwards, while it sat there invisibly.

Java's default — each object equal only to itself — is exactly right here. The same question,
answered oppositely in two neighbouring classes, because the underlying situation genuinely differs.

---

## Three methods instead of setters

```java
public void markPublished(Instant when)
public void recordFailure(String error)
public void park()
```

Rather than exposing a setter per field, the record offers the three things that can legitimately
happen to it. That means a reader can see every way this row's state can change by looking at one
short list.

`markPublished` sets the status **and** the timestamp together, because the database requires it —
the `CHECK` constraint from T063 says `published_at` is non-null exactly when the status is
`PUBLISHED`. A setter per field would let code set one and forget the other, and get a constraint
violation from somewhere confusing.

`recordFailure` increments the attempt count and stores the reason. Note what it does **not** do: it
does not decide whether the count has grown too high. Counting is the record's business; deciding
when the count is too high is the relay's. That line matters — the relay is the part being written by
hand in T099, and this class deliberately leaves that judgement to it.

---

## Phase 2 so far

The schema exists and the Java classes that map to it exist. They have not yet been shown to agree —
that is T072, once the repositories (T068, T069) and a test harness with a real PostgreSQL container
(T071) are in place.
