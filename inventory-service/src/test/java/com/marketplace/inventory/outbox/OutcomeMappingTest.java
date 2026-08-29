package com.marketplace.inventory.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.marketplace.events.RejectionReason;
import com.marketplace.events.SagaEvent;
import com.marketplace.events.SeatsRejected;
import com.marketplace.events.SeatsReserved;
import com.marketplace.inventory.service.ReservationOutcome;

/**
 * Specifies {@link OutboxWriter}'s pure mapping from a decided {@link ReservationOutcome} to the
 * exact message it produces — written and failing to compile until {@code ReservationOutcome} (T158)
 * and {@code OutboxWriter} (T159) exist.
 *
 * <p>A UNIT test, deliberately: this exercises no database, no Redis, no Spring context. The mapping
 * from outcome to message is pure data transformation, and {@code ReservationOutcome} being a sealed
 * interface with exactly two cases is what makes the switch inside {@code OutboxWriter} exhaustive by
 * construction — adding a third outcome kind without also handling it here becomes a compile error,
 * not a silently unhandled branch discovered in production (data-model.md's own framing for why the
 * mapping must be "total").
 */
class OutcomeMappingTest {

	private static final UUID ORDER_ID = UUID.randomUUID();
	private static final Instant OCCURRED_AT = Instant.parse("2026-01-01T00:00:00Z");

	@Test
	void reservedMapsToSeatsReservedWithSagaIdEqualToOrderId() {
		Instant lockExpiresAt = OCCURRED_AT.plusSeconds(120);
		ReservationOutcome outcome = new ReservationOutcome.Reserved(UUID.randomUUID(), lockExpiresAt);

		SagaEvent message = OutboxWriter.toMessage(ORDER_ID, List.of("A1", "A2"), outcome, OCCURRED_AT);

		assertThat(message).isInstanceOf(SeatsReserved.class);
		SeatsReserved reserved = (SeatsReserved) message;
		assertThat(reserved.sagaId()).isEqualTo(ORDER_ID);
		assertThat(reserved.orderId()).isEqualTo(ORDER_ID);
		assertThat(reserved.occurredAt()).isEqualTo(OCCURRED_AT);
		assertThat(reserved.lockExpiresAt()).isEqualTo(lockExpiresAt);
	}

	@Test
	void rejectedMapsToSeatsRejectedWithSagaIdEqualToOrderId() {
		ReservationOutcome outcome = new ReservationOutcome.Rejected(RejectionReason.SEATS_ALREADY_HELD);

		SagaEvent message = OutboxWriter.toMessage(ORDER_ID, List.of("A1", "A2"), outcome, OCCURRED_AT);

		assertThat(message).isInstanceOf(SeatsRejected.class);
		SeatsRejected rejected = (SeatsRejected) message;
		assertThat(rejected.sagaId()).isEqualTo(ORDER_ID);
		assertThat(rejected.orderId()).isEqualTo(ORDER_ID);
		assertThat(rejected.occurredAt()).isEqualTo(OCCURRED_AT);
		assertThat(rejected.reason()).isEqualTo(RejectionReason.SEATS_ALREADY_HELD);
	}

	@Test
	void everyReachableRejectionReasonMapsToItsOwnCauseAndNoOther() {
		for (RejectionReason reason : RejectionReason.values()) {
			ReservationOutcome outcome = new ReservationOutcome.Rejected(reason);
			SeatsRejected message = (SeatsRejected) OutboxWriter.toMessage(
					ORDER_ID, List.of("A1"), outcome, OCCURRED_AT);
			assertThat(message.reason()).as("reason for %s", reason).isEqualTo(reason);
		}
	}

	@Test
	void seatIdsAreSortedRegardlessOfRequestOrder() {
		ReservationOutcome outcome = new ReservationOutcome.Reserved(
				UUID.randomUUID(), OCCURRED_AT.plusSeconds(120));

		SeatsReserved reserved = (SeatsReserved) OutboxWriter.toMessage(
				ORDER_ID, List.of("B2", "A1", "C3"), outcome, OCCURRED_AT);

		assertThat(reserved.seatIds()).containsExactly("A1", "B2", "C3");
	}

	@Test
	void aRejectionReportsTheFullRequestedSetNotOnlyWhicheverSeatWasUnavailable() {
		// FR-023: a refusal names every seat originally requested, because the request was refused as
		// a unit -- reporting only the contended seat would understate what the buyer actually asked
		// for and lost.
		ReservationOutcome outcome = new ReservationOutcome.Rejected(RejectionReason.SEATS_ALREADY_HELD);

		SeatsRejected rejected = (SeatsRejected) OutboxWriter.toMessage(
				ORDER_ID, List.of("A1", "A2", "A3"), outcome, OCCURRED_AT);

		assertThat(rejected.seatIds()).containsExactlyInAnyOrder("A1", "A2", "A3");
	}

	@Test
	void lockExpiresAtIsAlwaysStrictlyAfterOccurredAt() {
		// Both fields must derive from one instant at write time (FR-009); the contract's own compact
		// constructor already refuses to build a SeatsReserved otherwise, so a mapping that got this
		// wrong would surface as an exception right here, not as a message quietly violating its own
		// contract on the wire.
		Instant lockExpiresAt = OCCURRED_AT.plusSeconds(120);
		ReservationOutcome outcome = new ReservationOutcome.Reserved(UUID.randomUUID(), lockExpiresAt);

		SeatsReserved reserved = (SeatsReserved) OutboxWriter.toMessage(
				ORDER_ID, List.of("A1"), outcome, OCCURRED_AT);

		assertThat(reserved.lockExpiresAt()).isAfter(reserved.occurredAt());
	}
}
