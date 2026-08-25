# T103: the shape of a response, before there's a route to send it from

`OrderView` is a small, plain Java `record` — nothing runs it yet (that's T104), it just describes
what a successful `GET /api/orders/{orderId}` response looks like, and knows how to build itself from
a real `Order`.

## Why `amount` is a `String` here, even though `Order` stores it as a `BigDecimal`

The database stores money as `BigDecimal` (`NUMERIC(19,2)`), because a `BigDecimal` never loses
precision the way a binary floating-point number would — `0.10` really is `0.10`, not something close
to it. That's the right type to compute with.

But `contracts/orders-api.yaml` says the *wire* shape of `amount` is a JSON string: `"150.00"`, with
the quotes. That's deliberate too — the exact same reasoning `CreateOrderRequest` already uses for the
amount a caller *submits*: a JSON string can never accidentally become a floating-point number
somewhere between the server and whatever eventually reads the response, the way a bare JSON number
sometimes can in less careful clients.

`CreateOrderRequest` gets away with declaring its field as `BigDecimal` anyway, because Jackson's
`BigDecimal` *deserializer* — reading the request in — happily accepts either a quoted string or a
bare number on the way in. But `OrderView` only ever goes the other direction: Java data becoming
JSON text. Jackson's default *serializer* for a `BigDecimal` writes a bare number, not a string, and
nothing here would silently fix that. So `OrderView.amount` is declared as a plain `String`, and
`OrderView.from(order)` converts it explicitly with `order.getAmount().toPlainString()` — a method
that turns a `BigDecimal` into exactly the decimal text it represents, with no exponent notation and
no ambiguity about what type is going out over the wire.

## Why the seats get sorted here

An `Order`'s seats are stored in a `Set`, and a `Set` makes no promise about what order you get its
elements back in — asking the same order for its seats twice, even without changing anything, could
in principle produce two different orderings. That's a mildly annoying but real problem for a *read*
endpoint: two people looking at the very same order, seconds apart, could see two different-looking
responses, and a test asserting on the seat list would be fragile against JVM/collection internals
rather than against anything that actually matters. `OrderView.from` sorts the seats once, right when
it builds the response, so `GET`ting the same order always looks the same.
