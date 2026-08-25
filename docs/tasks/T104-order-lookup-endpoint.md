# T104: wiring the GET route, and letting Spring do the identifier parsing for you

T103 gave the response a shape. T104 gives it a route: `GET /api/orders/{orderId}` on the same
`OrderController` that already handles `POST /api/orders`.

## The method itself

```java
@GetMapping("/{orderId}")
public OrderView getOrder(@PathVariable UUID orderId) {
    Order order = orderRepository.findById(orderId).orElseThrow(() -> new OrderNotFoundException(orderId));
    return OrderView.from(order);
}
```

Three lines, and each one is doing something worth noticing.

**`@PathVariable UUID orderId`, not `@PathVariable String orderId`.** Declaring the parameter as a
`UUID` directly, instead of a `String` this method would parse itself, hands the conversion to Spring
before the method body ever runs. That single choice is what makes FR-021's two different failures
genuinely separable later: something that isn't a valid UUID at all (`"not-a-uuid"`) never makes it
as far as this method — it fails during Spring's own argument binding, an entirely different moment
and a different kind of failure than "this UUID is fine, but nothing exists with it." Writing
`String orderId` and parsing it by hand here would collapse those two distinct failures back into one
code path, and the rest of T105 would have had to reinvent the distinction this one type choice
already gives for free.

**`orElseThrow(() -> new OrderNotFoundException(orderId))`.** A brand-new small exception (also added
in this task, since the controller needs somewhere to signal "no such order" to), thrown only once
the repository has genuinely looked and found nothing. `OrderNotFoundException` doesn't do anything
clever — see T105 for what actually turns it into an HTTP response.

**`OrderView.from(order)`.** Nothing here builds the response by hand field by field; that mapping,
including the seat-sorting, already lives in `OrderView` itself (T103). The controller's only job is
to find the right `Order` and hand it off.

## What this doesn't do yet

Right now, throwing `OrderNotFoundException` produces a bare 500 — Spring has nothing registered that
knows what to do with it. That's expected and temporary: T105 is the very next task, and it's the one
that teaches the exception handler what a "no such order" response should actually look like. Wiring
the route and writing the handler are kept as two separate, ordered tasks on purpose, the same
discipline this whole build step has used throughout: one thing changes, then it's checked, then the
next thing changes.
