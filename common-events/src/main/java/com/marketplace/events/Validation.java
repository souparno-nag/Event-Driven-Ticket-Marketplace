package com.marketplace.events;

import java.math.BigDecimal;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The rules every message enforces in its canonical constructor.
 *
 * <p>WHY validation lives in the constructor rather than in a validator a caller may or may not
 * invoke: a message that cannot exist in an invalid state removes an entire category of
 * defensive checking from every consumer. A consumer holding an {@code OrderCreated} knows its seat
 * list is non-empty and duplicate-free because there is no way to have built one otherwise. The
 * alternative — validating on receipt — puts the same checks in seven consumers and relies on all
 * seven remembering.
 *
 * <p>WHY package-private rather than public: these are the contract module's own invariants, not a
 * validation library. Publishing them would invite services to call them on their own types, which
 * couples those types to rules written for messages and makes the rules harder to change later.
 *
 * <p>On exception types: null arguments throw {@link NullPointerException} and everything else
 * throws {@link IllegalArgumentException}. That split is the JDK's own convention — see
 * {@link Objects#requireNonNull} — and following it means a stack trace reads the way a Java
 * developer already expects rather than the way this project decided to be different.
 */
final class Validation {

	/**
	 * Rule 1: every {@code UUID}, {@code Instant}, and enum component is non-null.
	 *
	 * <p>WHY a wrapper around {@code Objects.requireNonNull} at all: the message. The JDK's version
	 * throws with no detail unless a name is passed, and "null" alone in a stack trace from a
	 * seven-component record leaves the reader guessing which component it was.
	 */
	static <T> T requireNonNull(T value, String name) {
		return Objects.requireNonNull(value, name + " must not be null");
	}

	/**
	 * Rule 2: {@code sagaId} equals {@code orderId}.
	 *
	 * <p>WHY the two are checked against each other rather than one being derived from the other:
	 * they mean different things. {@code orderId} names the order; {@code sagaId} correlates the
	 * conversation about it and is the partition key. They coincide in this system by design, and
	 * asserting it keeps a message that would silently break per-order ordering from ever existing —
	 * a mismatched key routes a message to the wrong partition, where it arrives out of order
	 * relative to the rest of its saga and nothing reports an error.
	 */
	static void requireSagaMatchesOrder(UUID sagaId, UUID orderId) {
		requireNonNull(sagaId, "sagaId");
		requireNonNull(orderId, "orderId");
		if (!sagaId.equals(orderId)) {
			throw new IllegalArgumentException(
					"sagaId must equal orderId, but sagaId=" + sagaId + " and orderId=" + orderId);
		}
	}

	/**
	 * Rule 3: {@code seatIds} is non-empty, duplicate-free, and copied into an unmodifiable list.
	 *
	 * <p>Returns the copy so a record can assign the result directly. WHY copying matters: a record
	 * component holding a caller's {@code ArrayList} is only as immutable as the caller's
	 * discipline — they keep their reference and can still add to it after publishing. Copying
	 * severs that, which is what makes FR-005's immutability real rather than a convention.
	 *
	 * <p>WHY duplicates are rejected rather than silently collapsed: a request for {@code [A1, A1]}
	 * is a caller bug, and quietly turning it into {@code [A1]} means an order charged for two seats
	 * that reserved one. Failing loudly at construction is the only outcome that cannot end in a
	 * customer paying for a seat they do not have.
	 */
	static List<String> requireNonEmptyDistinctSeats(List<String> seatIds) {
		requireNonNull(seatIds, "seatIds");
		if (seatIds.isEmpty()) {
			throw new IllegalArgumentException("seatIds must not be empty");
		}
		// A Set of the same contents is smaller only when something appeared twice. Cheaper and
		// clearer than sorting or a nested loop, and it names the offending seat in the message.
		Set<String> seen = new HashSet<>();
		for (String seatId : seatIds) {
			requireNonNull(seatId, "seatId");
			if (!seen.add(seatId)) {
				throw new IllegalArgumentException("seatIds must not contain duplicates: " + seatId);
			}
		}
		// List.copyOf returns an unmodifiable list, so neither the caller nor a consumer can mutate
		// the component after construction.
		return List.copyOf(seatIds);
	}

	/**
	 * Rule 4: money is non-negative with a scale of exactly 2.
	 *
	 * <p>WHY the scale is pinned rather than merely bounded: {@link BigDecimal#equals} compares
	 * scale as well as value, so {@code 2.5} and {@code 2.50} are equal numerically but
	 * <em>unequal</em> as objects. Records derive {@code equals} from their components, so without a
	 * canonical scale a message could round-trip through JSON and come back unequal to itself,
	 * failing FR-006 for a reason that looks nothing like a serialization bug. Fixing the scale at
	 * construction makes the representation canonical rather than incidental.
	 *
	 * <p>The second beneficiary is the payment rule this project simulates, which reads the last
	 * digit of the amount. A fixed scale means "the last digit" is unambiguous.
	 *
	 * <p>Zero is permitted. A free ticket is a legitimate order; a negative one is a refund wearing
	 * an order's clothes, and refunds are not part of this saga.
	 */
	static BigDecimal requireMoney(BigDecimal amount, String name) {
		requireNonNull(amount, name);
		if (amount.signum() < 0) {
			throw new IllegalArgumentException(name + " must not be negative, but was " + amount);
		}
		if (amount.scale() != 2) {
			throw new IllegalArgumentException(
					name + " must have scale exactly 2, but " + amount + " has scale " + amount.scale());
		}
		return amount;
	}

	/**
	 * Rule 5: {@code schemaVersion} is at least 1.
	 *
	 * <p>WHY the floor is 1 and not 0: {@code 0} is what an uninitialised {@code int} is, so
	 * accepting it would make "the producer forgot to set a version" indistinguishable from a
	 * deliberate version zero. Starting at 1 turns that mistake into a rejected construction.
	 */
	static int requireSchemaVersion(int schemaVersion) {
		if (schemaVersion < 1) {
			throw new IllegalArgumentException("schemaVersion must be at least 1, but was " + schemaVersion);
		}
		return schemaVersion;
	}

	// Not instantiable: a namespace for static rules, with no state of its own.
	private Validation() {
	}
}
