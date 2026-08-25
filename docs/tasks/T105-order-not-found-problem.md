# T105: two different 400s and 404s, told apart by more than a number

FR-021 asks for something specific: a caller looking up an order that doesn't exist, and a caller who
sent something that isn't even a valid identifier, must be told apart — not just by two different
HTTP status codes, but by two genuinely different `type` values in the response body, the way this
whole API already reports every other kind of failure (see `ApiExceptionHandler`'s existing handlers
for validation failures and capacity refusals).

## The straightforward half: "no such order"

```java
@ExceptionHandler(OrderNotFoundException.class)
public ProblemDetail handleOrderNotFound(OrderNotFoundException ex) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    problem.setTitle("Order not found");
    problem.setType(ORDER_NOT_FOUND_TYPE);
    return problem;
}
```

`OrderController` (T104) throws `OrderNotFoundException` once it's already looked in the database and
found nothing. This handler catches exactly that, and nothing else, and reports it as a 404 with its
own stable `type` URI.

## The half that took a wrong turn first: "that isn't even an identifier"

The obvious-looking way to get the *other* failure — a path segment like `/api/orders/not-a-uuid`
that isn't a UUID at all — reported as a proper `application/problem+json` response is a single
Spring Boot setting: `spring.mvc.problemdetails.enabled: true`. It's off by default in this version of
Spring Boot, and turning it on makes Spring's own built-in exception handling produce RFC 7807 bodies
automatically for failures nothing in this codebase has to write a line of code for.

That was tried first. It broke six existing, previously-passing tests in `OrderApiIT` — the ones
checking that a validation failure names the offending field, e.g. `response.field == "seatIds"`.
The reason is a genuinely useful thing to understand about how Spring resolves `@ExceptionHandler`
methods: turning on that setting registers *another* piece of code, owned by Spring Boot itself, that
also knows how to handle the exact same exception type (`MethodArgumentNotValidException`) this
project's own `ApiExceptionHandler` already had a handler for. When two different handlers both claim
they can handle the same exception, Spring has to pick one — and it picked Spring Boot's own generic
one over this project's more specific one, silently dropping the custom `field` property every one of
those tests depended on. Nothing crashed and nothing logged a warning; the responses just quietly
stopped carrying the one piece of information those tests needed, which is exactly the kind of failure
that's easy to miss if you only run the *new* test and never check whether anything old broke.

The fix: revert that global setting, and instead write one more `@ExceptionHandler`, right alongside
the ones already in this file, for the exact exception Spring throws when path-variable conversion
fails (`MethodArgumentTypeMismatchException`):

```java
@ExceptionHandler(MethodArgumentTypeMismatchException.class)
public ProblemDetail handleMalformedIdentifier(MethodArgumentTypeMismatchException ex) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST, "'" + ex.getValue() + "' is not a well-formed identifier");
    problem.setTitle("Malformed identifier");
    problem.setType(MALFORMED_IDENTIFIER_TYPE);
    return problem;
}
```

A few more lines than flipping one setting, but it stays entirely inside a class this project already
owns and already understands, rather than depending on how Spring Boot orders one autoconfigured
piece of exception handling against another — a detail that isn't visible from reading this project's
own code at all, and that changed behavior it wasn't obviously connected to.

## Why the two never collide

Neither handler above ever fires for the other's situation. `MethodArgumentTypeMismatchException` is
thrown by Spring itself, before `OrderController#getOrder` ever runs — a malformed identifier never
gets far enough to be looked up, so `OrderNotFoundException` can't be thrown for it.
`OrderNotFoundException` is only ever thrown by this project's own code, only after a lookup that
requires a successfully-parsed `UUID` in the first place. The two exceptions describe two different
moments in the request's life, which is what makes them reportable as two honestly different
problems rather than one problem wearing two different status codes.
