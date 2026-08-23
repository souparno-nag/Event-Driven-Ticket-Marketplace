# T083 — `OrderController`, and the HTTP contract finally answers

**What this task did:** wrote `POST /api/orders`, which turns `OrderAcceptanceIT`'s test-only method
call into a real HTTP endpoint — the piece `OrderApiIT` and `OrderCapacityIT` have been failing
against (with an honest 404) since the last batch.

```java
@PostMapping
public ResponseEntity<CreateOrderResponse> createOrder(@Valid @RequestBody CreateOrderRequest request) {
    UUID orderId = orderAcceptanceService.acceptOrder(request);

    return ResponseEntity
            .accepted()
            .location(URI.create("/api/orders/" + orderId))
            .body(new CreateOrderResponse(orderId, OrderStatus.PENDING));
}
```

---

## `@Valid`, and where the 400s actually come from

`@Valid` in front of `@RequestBody` is the one line connecting everything T079 built to this
endpoint. Without it, Spring would deserialize the JSON body into a `CreateOrderRequest` and hand it
straight to the controller method — the Bean Validation annotations on the record would exist but
never actually run. With it, Spring validates the constructed object *before* the method body ever
executes, and a violation throws `MethodArgumentNotValidException` — which `ApiExceptionHandler`
(T084) catches and turns into the `400` responses `OrderApiIT` has been checking for since the last
batch.

This controller method contains **no validation logic of its own**. Every rejection this endpoint
produces was already specified by `CreateOrderRequest`'s annotations; this method only exists once
that check has already passed.

## `202 Accepted`, not `201 Created`

The controller returns `ResponseEntity.accepted()`. `201 Created` is the conventional response to a
successful `POST`, and it would be wrong here on purpose. By the time this method returns, the order
row genuinely exists — but the *booking* does not: no seats are held, no payment has moved, and the
saga that decides whether either of those things happens hasn't even started. `201 Created` would
tell a buyer they have seats. `202 Accepted` says, accurately, "your request has been accepted for
processing" — which is the entire truth this response is entitled to state.

## The `Location` header

```java
.location(URI.create("/api/orders/" + orderId))
```

Points at where the order can eventually be read back — the endpoint User Story 3 adds in T104. It
doesn't exist yet, so following this header today gets a `404`; the header is correct regardless,
because it names where the resource *will* live, not a promise that it answers right now.

---

## Confirmed

Both `OrderApiIT` and `OrderCapacityIT` — sitting since the last batch with clean, honest 404
failures — now pass without either test file changing:

```text
Tests run: 7, Failures: 0, Errors: 0 -- OrderApiIT
Tests run: 1, Failures: 0, Errors: 0 -- OrderCapacityIT
```

`OrderCapacityIT` passing here is worth noting specifically: this controller has no capacity-handling
code in it at all. It passes because `OrderCapacityIT` saturates the connection pool *before* any
request reaches this method, and Spring's own transaction machinery throws before this controller's
body ever runs — the 503 comes entirely from `ApiExceptionHandler` (T084), not from anything written
here.
