package com.marketplace.inventory.consume;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * The composite identity of one message having been processed by one consumer:
 * {@code (messageId, consumerName)}, matching {@code processed_messages}' own composite primary key
 * in {@code V3__create_processed_messages.sql} exactly.
 *
 * <p>The key is composite rather than {@code messageId} alone — the first of two deliberate
 * deviations from the original brief, recorded in full in {@code V3__create_processed_messages.sql}
 * and {@code research.md} R7: a single-column key would let the first consumer in this database to
 * handle a message silently lock every other consumer out of it, for a message that consumer has
 * never actually seen. This service has exactly one consumer today, so nothing breaks yet — the bug
 * lands the day a second one reads a channel a first one already reads.
 *
 * <p>The field is {@code messageId}, not {@code eventId} — the second deviation. Step 1 removed
 * "event" as a field name specifically because it was ambiguous between a message and a concert; a
 * field called {@code eventId} in the one class whose entire job is identifying MESSAGES would
 * reintroduce exactly that ambiguity.
 *
 * <p>Both fields are decided once, at the moment a message is first processed, and never change
 * afterward, so hand-written value-based {@code equals}/{@code hashCode} — matching
 * {@link com.marketplace.inventory.domain.ShowSeatId} and
 * {@link com.marketplace.inventory.domain.ReservationSeatId}, this service's other two
 * {@code @EmbeddedId} classes — is exactly what is required here.
 */
@Embeddable
public class ProcessedMessageId implements Serializable {

	@Column(name = "message_id")
	private UUID messageId;

	@Column(name = "consumer_name", length = 64)
	private String consumerName;

	/** Required by JPA. Not for application code. */
	protected ProcessedMessageId() {
	}

	public ProcessedMessageId(UUID messageId, String consumerName) {
		this.messageId = Objects.requireNonNull(messageId, "messageId");
		this.consumerName = Objects.requireNonNull(consumerName, "consumerName");
	}

	public UUID getMessageId() {
		return messageId;
	}

	public String getConsumerName() {
		return consumerName;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof ProcessedMessageId that)) {
			return false;
		}
		return messageId.equals(that.messageId) && consumerName.equals(that.consumerName);
	}

	@Override
	public int hashCode() {
		return Objects.hash(messageId, consumerName);
	}
}
