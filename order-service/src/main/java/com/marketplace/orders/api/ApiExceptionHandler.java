package com.marketplace.orders.api;

import java.net.URI;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * Turns known failures into RFC 7807 {@link ProblemDetail} responses with stable {@code type} URIs,
 * per {@code contracts/orders-api.yaml}: a malformed request, a system at capacity, an order looked
 * up by an identifier nothing matches, and — since T105 — a path variable that isn't even the right
 * shape to look anything up by.
 *
 * <p>WHY a stable {@code type} URI rather than relying on the HTTP status code alone: FR-036 needs a
 * capacity refusal to be machine-distinguishable from a bad request, and a status code by itself is
 * not guaranteed stable once the step-7 gateway sits in front of this service and may rewrite or wrap
 * responses. A {@code type} URI travels with the body and survives that.
 *
 * <p>TRADEOFF: FR-021's "malformed identifier" problem is handled by an explicit
 * {@code @ExceptionHandler} here, rather than by turning on Spring Boot's global
 * {@code spring.mvc.problemdetails.enabled} switch (which would report it for free, since path
 * variable conversion already fails with an exception Spring's own default resolver knows how to
 * turn into a 400). That switch was tried first and reverted: it also changes how EVERY other
 * exception in this class is resolved, and it demoted {@code handleValidationFailure} below to lose
 * to Spring's own generic handler for the exact same exception type — silently dropping the
 * {@code field} property every existing test in {@code OrderApiIT} already depended on. An explicit
 * handler here costs a few more lines and stays entirely inside this class's own control, rather
 * than depending on how Spring orders one autoconfigured advice bean against another.
 */
@RestControllerAdvice
public class ApiExceptionHandler {

	private static final URI VALIDATION_FAILED_TYPE =
			URI.create("https://ticket-marketplace/problems/validation-failed");

	private static final URI CAPACITY_EXCEEDED_TYPE =
			URI.create("https://ticket-marketplace/problems/capacity-exceeded");

	private static final URI ORDER_NOT_FOUND_TYPE =
			URI.create("https://ticket-marketplace/problems/order-not-found");

	private static final URI MALFORMED_IDENTIFIER_TYPE =
			URI.create("https://ticket-marketplace/problems/malformed-identifier");

	private final CapacityMetrics capacityMetrics;

	public ApiExceptionHandler(CapacityMetrics capacityMetrics) {
		this.capacityMetrics = capacityMetrics;
	}

	/**
	 * Bean Validation failure on {@code @Valid @RequestBody}. Names the first offending field
	 * (FR-005) — every current caller of this endpoint submits at most one invalid field per request,
	 * so "first" and "only" coincide; a request failing several rules at once still gets a single,
	 * readable complaint rather than none.
	 */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ProblemDetail handleValidationFailure(MethodArgumentNotValidException ex) {
		FieldError fieldError = ex.getBindingResult().getFieldErrors().stream().findFirst().orElse(null);

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.BAD_REQUEST,
				fieldError != null ? fieldError.getDefaultMessage() : "Validation failed");
		problem.setTitle("Validation failed");
		problem.setType(VALIDATION_FAILED_TYPE);
		if (fieldError != null) {
			// A flat, top-level property (Spring's ProblemDetail Jackson support serialises extension
			// members at the top level, not nested), so a caller reads response.field directly rather
			// than parsing a nested violations array to find the one thing that went wrong.
			problem.setProperty("field", fieldError.getField());
		}
		return problem;
	}

	/**
	 * Covers both halves of FR-035: the connection pool refusing to hand out a connection
	 * ({@code CannotCreateTransactionException}) and a transaction that ran past its own timeout
	 * ({@code TransactionTimedOutException}) — both are {@link TransactionException} subtypes, and
	 * both mean the same thing to a caller: retry shortly, this was not about the request's content.
	 */
	@ExceptionHandler(TransactionException.class)
	public ResponseEntity<ProblemDetail> handleCapacityExceeded(TransactionException ex) {
		capacityMetrics.recordCapacityRefusal();

		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.SERVICE_UNAVAILABLE, "No capacity to record this request; retry shortly");
		problem.setTitle("Service busy");
		problem.setType(CAPACITY_EXCEEDED_TYPE);

		return ResponseEntity
				.status(HttpStatus.SERVICE_UNAVAILABLE)
				.header(HttpHeaders.RETRY_AFTER, "1")
				.body(problem);
	}

	/**
	 * FR-021: a well-formed identifier that simply doesn't belong to any order. Distinct in every
	 * sense from the 400 {@link #handleMalformedIdentifier} produces — a different status, a
	 * different {@code type}, and a different triggering exception entirely.
	 */
	@ExceptionHandler(OrderNotFoundException.class)
	public ProblemDetail handleOrderNotFound(OrderNotFoundException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
		problem.setTitle("Order not found");
		problem.setType(ORDER_NOT_FOUND_TYPE);
		return problem;
	}

	/**
	 * FR-021's other half: {@code orderId} in the path failed to convert to a {@link
	 * java.util.UUID} at all — thrown by Spring's own argument resolution, before
	 * {@code OrderController#getOrder} ever runs, which is exactly what keeps this genuinely
	 * distinct from {@link #handleOrderNotFound}: a malformed identifier never reaches that handler,
	 * and an unknown-but-well-formed one never reaches this one.
	 */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ProblemDetail handleMalformedIdentifier(MethodArgumentTypeMismatchException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(
				HttpStatus.BAD_REQUEST, "'" + ex.getValue() + "' is not a well-formed identifier");
		problem.setTitle("Malformed identifier");
		problem.setType(MALFORMED_IDENTIFIER_TYPE);
		return problem;
	}
}
