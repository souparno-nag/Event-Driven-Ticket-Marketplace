# T079 — `CreateOrderRequest`, the first class the tests were waiting for

**What this task did:** wrote the record `CreateOrderRequestValidationTest` (T073) had been
specifying since the last batch, plus two small custom validation rules Bean Validation does not
provide out of the box.

---

## The record itself

```java
public record CreateOrderRequest(
        @NotNull UUID userId,
        @NotNull UUID showId,
        @NotEmpty @UniqueElements List<@Size(max = 32) String> seatIds,
        @NotNull @DecimalMin(value = "0.00") @TwoDecimalPlaces BigDecimal amount) {
}
```

Four fields, matching `contracts/orders-api.yaml` exactly. Records support validation annotations
directly on their components — Java generates the corresponding field, constructor parameter, and
accessor from one declaration, and the constraint travels with all three.

`@Size(max = 32)` sits **inside** the `List<...>` type parameter rather than on `seatIds` itself.
That is a *container element constraint* — Bean Validation checking each element of a collection
individually, rather than the collection as a whole. `@NotEmpty` and `@UniqueElements`, by contrast,
sit on `seatIds` directly, because they are statements about the whole list, not about any one seat
label in it.

## Two rules Bean Validation doesn't ship with

**`@TwoDecimalPlaces`** — "exactly two decimal places" is not one of Bean Validation's built-in
constraints. `@Digits` bounds how many digits appear on each side of the decimal point but does not
pin the count after it, so `10.5` would pass `@Digits` fine while still being wrong for money. The
validator is three lines:

```java
public boolean isValid(BigDecimal value, ConstraintValidatorContext context) {
    return value == null || value.scale() == 2;
}
```

Returning `true` for `null` is the convention every Bean Validation constraint follows: a missing
value is `@NotNull`'s complaint, not this one's. Stacking both on `amount` means a missing amount and
a malformed one get to report cleanly, without two constraints arguing over the same `null`.

**`@UniqueElements`** — checks a collection has no duplicates, by comparing its size to a `HashSet`
built from it:

```java
return value == null || new HashSet<>(value).size() == value.size();
```

The `order_seats` table already makes a duplicate seat impossible to *persist* — its composite
primary key was built for exactly that in T062. This annotation is what turns the same mistake into
a same-request `400` naming `seatIds`, rather than a confusing database error surfacing several
layers deeper, after the request already looked accepted.

Both custom annotations live in a small `validation` subpackage — the annotation interface plus its
validator class, following the same two-file shape Bean Validation's own built-in constraints use.

---

## Why the amount is a `BigDecimal` even though the wire form is a string

The API contract sends `"amount": "10.00"` — a JSON *string*, quoted. `CreateOrderRequest.amount`
is typed `BigDecimal`, with no custom deserializer. That works because Jackson's default
`BigDecimal` deserializer already accepts either a JSON number or a JSON string and parses both the
same way. The string form on the wire exists for the *client's* benefit — it stops a client-side
JSON parser from ever turning the amount into a binary floating-point number before the request
even leaves the browser — and Jackson on this end reads either form correctly without being told to.

---

## Confirmed

`CreateOrderRequestValidationTest`, which has been sitting unable to compile since the last batch,
now compiles and passes in full:

```text
Tests run: 7, Failures: 0, Errors: 0 -- CreateOrderRequestValidationTest
```

Every rejection (empty seats, duplicate seats, missing buyer, missing show, negative amount, wrong
decimal scale) and the one acceptance test all pass, without that test file changing by a single
line — proof the class built here matches exactly what was specified in advance.
