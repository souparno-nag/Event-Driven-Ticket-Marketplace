# T073 — Specifying `CreateOrderRequest`'s validation, before it exists

**What this task did:** wrote a test file describing every way a booking request can be rejected —
for a Java class, `CreateOrderRequest`, that has not been created yet.

---

## Writing a test for a class that doesn't exist

This sounds backwards, and in most everyday programming it would be. Here it is deliberate: the
project's testing standard says a test must fail before its implementation exists and pass
afterward. In a language like Java, where every type is checked before the program even runs, the
strongest possible version of "fails before implementation" is **the file does not compile at all**.

That is exactly what happens here. `CreateOrderRequestValidationTest.java` references
`CreateOrderRequest`, and nothing by that name exists in the codebase yet. I ran the build to
confirm:

```text
CreateOrderRequestValidationTest.java:[107,76] cannot find symbol
  symbol:   class CreateOrderRequest
```

That error is the "red" of red-green-refactor. It will turn green only once T079 creates the class
this test describes — and the test's job right now is to say, precisely, what that class must do.

---

## What the test asks for, without saying how

```java
CreateOrderRequest request = new CreateOrderRequest(
        UUID.randomUUID(), UUID.randomUUID(), List.of(), new BigDecimal("25.00"));

assertThat(propertyPathsOf(validator.validate(request))).contains("seatIds");
```

Notice what is absent: no mention of any validation annotation, custom or built-in. The test
constructs an instance and asks a standard **Bean Validation** `Validator` what is wrong with it,
then checks that the complaint names the right field.

This is deliberate distance-keeping. Whether `CreateOrderRequest` ends up using a hand-written
`@UniqueElements` annotation, a different mechanism entirely, or something not yet imagined, is not
this test's concern. Only the *outcome* — "an empty seat list produces a complaint about `seatIds`"
— is. That is also exactly the question the HTTP layer (`ApiExceptionHandler`, built in T084) will
be answering for a real request, so this test is really specifying the behaviour two layers up the
stack will eventually rely on.

## The six rejections, and one acceptance

| Test | What it constructs | What must be named |
|---|---|---|
| `rejectsAnEmptySeatList` | no seats | `seatIds` |
| `rejectsDuplicateSeats` | `["A1", "A1"]` | `seatIds` |
| `rejectsAMissingBuyer` | `userId = null` | `userId` |
| `rejectsAMissingShow` | `showId = null` | `showId` |
| `rejectsANegativeAmount` | `-1.00` | `amount` |
| `rejectsAnAmountWithTheWrongNumberOfDecimalPlaces` | `25.5` (one decimal place, not two) | `amount` |
| `acceptsAWellFormedRequest` | everything valid | no complaints at all |

That last one matters as much as the six rejections. Without it, a validation rule that is
accidentally too strict — one that rejects a perfectly good order — would sail through undetected.

---

## `Validator`, not a hand-rolled check

```java
factory = Validation.buildDefaultValidatorFactory();
validator = factory.getValidator();
```

`Validation` and `Validator` come from **Bean Validation** (the `jakarta.validation` API, backed at
runtime by Hibernate Validator, added to this module's dependencies back in T058). This is the same
mechanism Spring Boot's `@Valid` annotation triggers automatically on an incoming HTTP request body,
so testing directly against a `Validator` here is testing exactly what will run in production —
without needing an HTTP server, a database, or a Spring context to do it. That is what keeps this a
fast, dependency-free unit test rather than an integration test.

The factory is closed in `@AfterAll` rather than left open, which is good hygiene for a resource that
holds onto reflection metadata for the lifetime it is used.
