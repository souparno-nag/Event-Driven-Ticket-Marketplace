# T066 — The `Order` entity

**What this task did:** wrote the Java class that maps to the `orders` table, and added Lombok to
the module so the getters do not have to be typed out.

---

## What "entity" means

An **entity** is a Java class that JPA maps to a database table. Annotations describe the mapping:

```java
@Entity
@Table(name = "orders")
public class Order {
    @Id
    private UUID id;
```

Load an `Order` and JPA runs the `SELECT` and fills in the fields. Change a field inside a
transaction and JPA works out the `UPDATE` for you. You mostly stop writing SQL — the exception in
this project being the relay's claim query, which does something SQL is uniquely good at.

---

## Lombok, and where it is dangerous

Writing a getter for each of nine fields is thirty lines of nothing. `@Getter` on the class generates
them at compile time.

What is **not** used here is `@Data`, `@EqualsAndHashCode`, or `@ToString`, and the reason is worth
understanding because it is a classic way to lose data:

- **`@EqualsAndHashCode`** builds a hash code from every field, including the id. For an entity whose
  id the database assigns, that id is `null` before saving and a number afterwards — so the object's
  hash code *changes during save*. Anything holding it in a `HashSet` or `HashMap` looks in the old
  bucket and no longer finds it. The object is still in the collection and is now invisible.
- **`@ToString`** prints every field, including related collections. Logging one object can trigger
  extra database queries, or throw because the connection has already closed.

`@Getter` and `@Setter` generate none of that and are entirely safe.

## Hand-written `equals` — and why it is allowed here

```java
public boolean equals(Object other) {
    ...
    return id.equals(that.id);
}
```

This is the pattern just described as dangerous — with one difference that changes everything: **this
id is assigned by the application, in the constructor, before the object is ever saved.** It never
changes, so the hash code never changes, so nothing gets lost.

The `OutboxRecord` class written in the next task deliberately does the opposite, because its id
*is* database-generated. Same question, different answer, driven by a real difference rather than
taste.

---

## Other decisions

### Seats are eagerly loaded

```java
@ElementCollection(fetch = FetchType.EAGER)
@CollectionTable(name = "order_seats", joinColumns = @JoinColumn(name = "order_id"))
private Set<String> seatIds;
```

`@ElementCollection` maps a collection of plain values to a side table — no separate entity class
needed, which suits seat labels since they are just strings belonging to an order.

**Eager** means the seats are fetched with the order. The usual advice is lazy — fetch on first
access — because eager loading wastes work when you did not need the data. Eager is right here for a
specific reason: a lazy collection can only be loaded while the database session is open, and a
controller building a JSON response often runs *after* it closes. The result is
`LazyInitializationException`, which is one of the most common ways this pattern goes wrong. An
order has a handful of seats and is never read in bulk, so the join costs nothing worth defending
against.

### A defensive copy in the constructor

```java
this.seatIds = new LinkedHashSet<>(seatIds);
```

Without this, the caller keeps a reference to the very collection inside the order, and can add a
seat *after* the order has been validated. Copying at the boundary means the order owns its state.
`getSeatIds()` returns an unmodifiable view for the same reason, from the other direction.

### `@Version`

```java
@Version
private Long version;
```

One annotation, and JPA appends `AND version = ?` to every update and bumps the value. Two people
updating the same order concurrently: the first succeeds, the second matches zero rows and gets
`OptimisticLockingFailureException` instead of silently erasing the first.

Nothing updates an order yet. `changeStatus()` exists so that `OrderVersionIT` has something to
change — the only way to demonstrate the version column actually works. It deliberately enforces no
rules about which transitions are legal, because no transition exists yet; those guards belong in
step 4, written against real callers rather than imagined ones.

### `@PrePersist` and `@PreUpdate`

The table sets `created_at` and `updated_at` with `DEFAULT now()`. These callbacks set them on the
Java object too. Without them the object in memory has `null` timestamps until it is re-read from the
database — and the controller is about to turn that object into a JSON response.
