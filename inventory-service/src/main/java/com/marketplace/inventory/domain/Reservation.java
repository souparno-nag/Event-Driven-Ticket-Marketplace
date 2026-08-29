package com.marketplace.inventory.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;

/**
 * The durable record that an order holds a set of seats — the authoritative truth this service
 * arbitrates contention against, with Redis acting only as a fast cache of it (FR-014;
 * {@code research.md} R4).
 *
 * <p>Deliberately carries {@code showId} as a plain column rather than a {@code @ManyToOne} to
 * {@link Show}, matching how order-service's own {@code Order} carries {@code showId} as a plain
 * column rather than a relationship to anything. Nothing in this service ever navigates from a
 * reservation to its show as an object graph; every read of {@code showId} is a value used directly
 * in a Redis key or an outbox payload, not a path to more entity state.
 *
 * <p>Similarly carries no foreign key or relationship into order-service's own {@code orders} table.
 * The two services agree about an order only by exchanging messages — a cross-schema foreign key
 * would make this service's writes depend on another service's schema being reachable, which is
 * exactly the coupling a choreographed saga exists to avoid.
 */
@Entity
@Table(name = "reservations")
@Getter
// Deliberately not @Data, @EqualsAndHashCode or @ToString, for the same reason as order-service's
// Order: Lombok's generated equals/hashCode would fold in the identifier, and this identifier is
// assigned by the APPLICATION before persist (see the constructor), so a folded-in hash code would
// still be stable across persist — but a hand-written equals below documents that fact rather than
// leaving it to be rediscovered.
public class Reservation {

	/**
	 * The value announced as {@code reservationId} on {@code SeatsReserved} (FR-011). Assigned by
	 * the application rather than the database, so the id exists before the row does and can be
	 * embedded in the outbox row written in the very same transaction (contracts/inventory-consumer.md).
	 */
	@Id
	@Column(name = "reservation_id")
	private UUID reservationId;

	/**
	 * UNIQUE at the database level ({@code V2__create_reservations.sql}) — a second line of defence
	 * behind the idempotency guard ({@code ProcessedMessage}, T129): even if that guard were somehow
	 * bypassed, the database itself still refuses a second reservation for one order.
	 */
	@Column(name = "order_id", nullable = false, unique = true)
	private UUID orderId;

	@Column(name = "show_id", nullable = false)
	private UUID showId;

	// STRING, not ORDINAL, matching every other status column in this project — see
	// ReservationStatus's own Javadoc for why an ordinal is the wrong choice here.
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private ReservationStatus status;

	/**
	 * The moment announced as {@code lockExpiresAt} on {@code SeatsReserved}. Computed from the SAME
	 * instant as the outbox row's {@code occurredAt} (a later task's {@code OutboxWriter}): the
	 * frozen contract requires the lapse to fall strictly after the announcement's own timestamp and
	 * refuses to construct otherwise, so deriving the two values at different times risks building a
	 * message that can never be sent (FR-009).
	 */
	@Column(name = "lock_expires_at", nullable = false)
	private Instant lockExpiresAt;

	/**
	 * Optimistic lock (FR-012). Hibernate appends {@code AND version = ?} to every update, so a
	 * writer working from a stale copy updates zero rows and is told it lost, rather than silently
	 * overwriting the winner. Retried exactly once on that failure (FR-013).
	 */
	@Version
	private Long version;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	/** Required by JPA, which instantiates entities reflectively. Not for application code. */
	protected Reservation() {
	}

	/**
	 * Creates a new reservation in {@link ReservationStatus#HELD}.
	 *
	 * <p>The caller supplies the id rather than letting the database generate one, for the same
	 * reason {@code Order}'s constructor does in order-service: the id is needed as a correlation
	 * key — here, the value embedded in the same-transaction outbox row — while the transaction is
	 * still open, and waiting for a database-generated value would mean flushing mid-transaction to
	 * find out what the reservation is called.
	 */
	public Reservation(UUID reservationId, UUID orderId, UUID showId, Instant lockExpiresAt) {
		this.reservationId = Objects.requireNonNull(reservationId, "reservationId");
		this.orderId = Objects.requireNonNull(orderId, "orderId");
		this.showId = Objects.requireNonNull(showId, "showId");
		this.lockExpiresAt = Objects.requireNonNull(lockExpiresAt, "lockExpiresAt");
		this.status = ReservationStatus.HELD;
	}

	// Both timestamps are set by the database's DEFAULT now() as well. The lifecycle callbacks exist
	// so the in-memory object matches the row without a re-read, matching order-service's Order.
	@PrePersist
	void onInsert() {
		Instant now = Instant.now();
		this.createdAt = now;
		this.updatedAt = now;
	}

	@PreUpdate
	void onUpdate() {
		this.updatedAt = Instant.now();
	}

	/**
	 * Moves this reservation to {@link ReservationStatus#EXPIRED}.
	 *
	 * <p>Deliberately a specific method rather than a generic {@code changeStatus}, unlike
	 * order-service's {@code Order}. That class chose genericity because none of its transitions had
	 * a real caller yet to write a guard against. This one does: the only transition reachable this
	 * build step is inline retirement of a lapsed reservation, contending for the same seats a new
	 * booking wants ({@code ReservationService}, a later task; FR-017, FR-018, R6). A named method
	 * documents that specific rule at its one call site rather than leaving "which transitions are
	 * even legal right now" implicit in whatever the caller happens to pass.
	 */
	public void expire() {
		this.status = ReservationStatus.EXPIRED;
	}

	/**
	 * Equality is by identifier, safe here for the same reason as {@code Order}: the id is assigned
	 * in the constructor, before the object is ever persisted, so it never changes — the hash code is
	 * stable and a {@code Reservation} can live in a {@code HashSet} across a {@code persist()}
	 * without being lost.
	 */
	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof Reservation that)) {
			return false;
		}
		return reservationId.equals(that.reservationId);
	}

	@Override
	public int hashCode() {
		return reservationId.hashCode();
	}
}
