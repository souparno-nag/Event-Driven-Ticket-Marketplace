package com.marketplace.inventory.consume;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * The durable note that a given message has already been handled by a given consumer — the sole
 * mechanism by which this service's at-least-once delivery is made safe (FR-028; spec.md Key
 * Entities).
 *
 * <p>This entity does not decide anything on its own. The actual guard — insert this row in the same
 * transaction as the state change it describes, and treat a constraint violation on the attempt as
 * "already handled" — is {@code IdempotencyGuard}, a later task deliberately left as a stub for the
 * developer to implement by hand (CLAUDE.md requirement 3; contracts/inventory-consumer.md). This
 * class only has to exist and map correctly for that guard to have something to insert.
 */
@Entity
@Table(name = "processed_messages")
@Getter
public class ProcessedMessage {

	@EmbeddedId
	private ProcessedMessageId id;

	@Column(name = "processed_at", nullable = false)
	private Instant processedAt;

	/** Required by JPA. Not for application code. */
	protected ProcessedMessage() {
	}

	public ProcessedMessage(UUID messageId, String consumerName) {
		this.id = new ProcessedMessageId(messageId, consumerName);
	}

	// The table's processed_at column is NOT NULL DEFAULT now(), but Hibernate generates an INSERT
	// naming every mapped column explicitly -- including this one, as NULL, if the Java field was
	// never set. An explicit NULL overrides the database's DEFAULT rather than falling through to
	// it, matching the identical reasoning on order-service's OutboxRecord.onInsert.
	@PrePersist
	void onInsert() {
		this.processedAt = Instant.now();
	}
}
