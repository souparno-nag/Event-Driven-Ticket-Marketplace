# T084 — `ApiExceptionHandler`, where 400 and 503 come from

**What this task did:** wrote the single class that turns two very different kinds of failure — a
malformed request, and a system with no capacity left — into the RFC 7807 responses
`contracts/orders-api.yaml` promises, each carrying a stable `type` that says which one happened.

---

## `@RestControllerAdvice`: one place, every controller

An `@ExceptionHandler` method inside `@RestControllerAdvice` catches exceptions thrown by **any**
controller in this service, not just `OrderController`. That matters for a reason beyond tidiness:
when `OrderController` (T104) grows a `GET` endpoint in User Story 3, it inherits this same error
handling automatically. Nobody has to remember to wire it up twice.

## Why a `type` URI, when the status code already says 400 versus 503

FR-036 needs a capacity refusal to be **machine-distinguishable** from a bad request — not just
readable by a human glancing at a status code, but reliably identifiable by code that has to decide
whether retrying makes sense. A status code alone is not quite enough for that once the step-7
gateway sits in front of this service: gateways sometimes rewrite or wrap responses in ways that
leave the original status less certain to survive intact. A `type` URI travels inside the response
body itself, so it survives whatever the gateway does to the envelope around it:

```java
private static final URI VALIDATION_FAILED_TYPE =
        URI.create("https://ticket-marketplace/problems/validation-failed");
private static final URI CAPACITY_EXCEEDED_TYPE =
        URI.create("https://ticket-marketplace/problems/capacity-exceeded");
```

## The validation handler: naming the field, not just the complaint

```java
@ExceptionHandler(MethodArgumentNotValidException.class)
public ProblemDetail handleValidationFailure(MethodArgumentNotValidException ex) {
    FieldError fieldError = ex.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);
    ...
    problem.setProperty("field", fieldError.getField());
```

`MethodArgumentNotValidException` is what `@Valid` throws when a `CreateOrderRequest` fails one of
T079's constraints. Spring's `ProblemDetail` return type is enough on its own here — return one from
an `@ExceptionHandler` method and Spring sets the response status and content type for you, with no
`ResponseEntity` wrapper needed. `setProperty("field", ...)` adds a custom member to the response;
Spring's `ProblemDetail` serialization places extension members at the **top level** of the JSON
object rather than nesting them, which is exactly what lets `OrderApiIT`'s
`jsonPath("$.field").value("seatIds")` find it directly.

Only the *first* violated field is named. Every test scenario written so far submits a request
invalid in exactly one way, so "first" and "only" coincide today; a request wrong in several places
at once would still get one clear complaint rather than none, which is the behaviour worth having
even before a test demands more.

## The capacity handler: two different exceptions, one meaning

```java
@ExceptionHandler(TransactionException.class)
public ResponseEntity<ProblemDetail> handleCapacityExceeded(TransactionException ex) {
    capacityMetrics.recordCapacityRefusal();
    ...
    return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
            .header(HttpHeaders.RETRY_AFTER, "1")
            .body(problem);
}
```

`TransactionException` is the parent type of two Spring exceptions that mean the same thing to a
caller even though they arise differently: `CannotCreateTransactionException`, thrown when the
connection pool has nothing left to hand out, and `TransactionTimedOutException`, thrown when a
transaction runs past the 3-second limit set in `application.yml`. Both say "this request could not
be recorded because the service is stretched thin right now" — never "your request was wrong" — so
both get the identical 503, `Retry-After: 1`, and a call to `CapacityMetrics` so the refusal is
counted rather than only logged.

This handler needs a `ResponseEntity` wrapper, unlike the validation one, because it adds a header
`ProblemDetail` alone has no way to carry.

---

## Confirmed

Both halves of `OrderApiIT`'s coverage and all of `OrderCapacityIT` now pass:

```text
Tests run: 7, Failures: 0, Errors: 0 -- OrderApiIT   (six 400s, one 202)
Tests run: 1, Failures: 0, Errors: 0 -- OrderCapacityIT   (503, Retry-After present, body names capacity)
```

The capacity test's assertion that the body **does not** contain `"validation-failed"` passes too —
proof the two `type` URIs are genuinely distinct, not just different in the one field a careless
implementation might have remembered to vary.
