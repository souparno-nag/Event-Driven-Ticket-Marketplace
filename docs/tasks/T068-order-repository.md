# T068 — The `OrderRepository`

**What this task did:** declared an interface. That is the entire change.

```java
public interface OrderRepository extends JpaRepository<Order, UUID> {
}
```

---

## Where the implementation is

There isn't one, and there is not meant to be. At startup Spring Data notices this interface,
generates a class implementing it, and puts an instance in the application context. You get
`save`, `findById`, `findAll`, `delete`, `count` and a couple of dozen more, none of them written
by anyone here.

The two type parameters are the entity and the type of its primary key — `Order` and `UUID`, the
identifier this application assigns rather than the database.

## Query methods, when they are needed

Spring Data also builds queries from **method names**. Declare this:

```java
List<Order> findByUserIdAndStatus(UUID userId, OrderStatus status);
```

and it parses the name, works out the query, and implements it. No SQL, no annotation.

This interface has none of those, because nothing needs one yet. Looking up an order by its id is
`findById`, which is inherited. Methods get added when a caller wants one, not in anticipation.

---

## Why there is no interface wrapping this interface

A common pattern is to hide Spring Data behind an interface of your own:

```java
public interface OrderStore {              // "so we could swap the database later"
    void save(Order order);
    Optional<Order> findById(UUID id);
}
```

That is not done here. The justification for such a wrapper is always portability — being able to
change the persistence technology without touching the rest of the code — and this service is not
going to change it. The outbox relay in build step 3 claims rows using `FOR UPDATE SKIP LOCKED`,
which is a PostgreSQL capability. The schema uses partial indexes and `jsonb`. Swapping the database
would mean rewriting the part of this service that matters most, and an extra interface would not
help.

The project constitution puts it directly: an abstraction has to be justified by a demonstrated
need, not a hypothetical one. This one would be justified by a need that has already been ruled out.
