package com.marketplace.inventory.consume;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;

import org.springframework.data.domain.Persistable;

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
 *
 * <p>WHY {@link Persistable}, found necessary directly rather than designed in up front:
 * {@link #id} is hand-assigned in the constructor, never {@code @GeneratedValue}, and never null by
 * the time a repository method sees this entity. Spring Data JPA's default "is this new?" check for an
 * entity with no {@code @Version} field is "is the id null?" — which for this entity is ALWAYS false,
 * so {@code save()}/{@code saveAndFlush()} were routing every call through
 * {@code EntityManager.merge()} rather than {@code persist()}. {@code merge()} first asks the
 * database whether a row with this id already exists before deciding insert or update, which is a
 * second, hidden read the guard's own contract does not call for (see
 * {@code ProcessedMessageRepository}'s Javadoc: "attempting the insert directly", not checking first)
 * — and was confirmed, by direct reproduction under concurrent redelivery, to occasionally build the
 * eventual INSERT from a copy of this entity taken before {@link #onInsert} had run, landing
 * {@code processed_at} as {@code NULL} against the column's own {@code NOT NULL} constraint instead of
 * cleanly raising the duplicate-key violation the guard is written to catch. Implementing
 * {@link Persistable} and always answering {@code true} from {@link #isNew()} tells Spring Data this
 * instance is unconditionally new, which forces {@code persist()} — the direct, single-INSERT path the
 * guard's contract was always written to assume — every time, matching the fact that a row in this
 * table is only ever inserted once and never updated afterward.
 */
@Entity
@Table(name = "processed_messages")
@Getter
public class ProcessedMessage implements Persistable<ProcessedMessageId> {

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

	@Override
	public ProcessedMessageId getId() {
		return id;
	}

	// See this class's own Javadoc for why: every instance is new, unconditionally, because a row
	// here is only ever inserted once and never updated -- there is no later point in this entity's
	// life where "new" should ever become false.
	@Override
	public boolean isNew() {
		return true;
	}
}
