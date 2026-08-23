# T080 — `CreateOrderResponse`

**What this task did:** wrote the two-field record that becomes the JSON body of a `202 Accepted`
response.

```java
public record CreateOrderResponse(UUID orderId, OrderStatus status) {
}
```

---

## Why this is worth its own task despite being two lines

It is small on purpose. There is nothing here beyond what `contracts/orders-api.yaml` asks for:
the new order's identifier, and its status. `status` is always `OrderStatus.PENDING` at the moment
this response is built — nothing else has run yet — and this record does not try to say otherwise.

## Enum serialization, with no extra work

Jackson serializes an enum as its `name()` by default, unless told to do something else with
`@JsonValue`. So `OrderStatus.PENDING` becomes the JSON string `"PENDING"` with zero configuration —
which is exactly the shape `OrderApiIT`'s `jsonPath("$.status").value("PENDING")` checks for, and it
passed on the first run with no adjustment needed here.

## Why a record, not a class with getters

A response body is a value — it has no identity, no behaviour, nothing that changes after
construction. A record is precisely Java's tool for that: two lines give a constructor, accessors,
`equals`, `hashCode`, and `toString`, none of them written by hand. There is no case here for
`@Getter` or any other Lombok annotation; a record already generates everything a DTO like this
needs, and generating it via the language rather than a library is one line simpler still.
