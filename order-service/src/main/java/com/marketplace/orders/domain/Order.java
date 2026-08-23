package com.marketplace.orders.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;

/**
 * A buyer's request to purchase specific seats for a show, and the aggregate the saga advances.
 *
 * <p>The identifier is assigned by the application rather than the database, and doubles as the saga
 * correlation id and the Kafka partition key. Every message about this order carries it, which is
 * what lets one order's messages be traced, correlated, and kept in order relative to each other.
 */
@Entity
@Table(name = "orders")
@Getter
// Deliberately not @Data, @EqualsAndHashCode or @ToString. Lombok's generated equals/hashCode fold
// in the identifier, so an entity's hash code changes the moment it is persisted and quietly breaks
// any HashSet already holding it; the generated toString walks lazy associations and causes
// surprise queries. equals and hashCode are written by hand below, for reasons that only hold
// because of how the id is assigned.
public class Order {

	@Id
	private UUID id;

	@Column(name = "user_id", nullable = false)
	private UUID userId;

	// showId, never eventId. In this system "event" means "a message"; a field that sometimes meant
	// a concert and sometimes meant message identity was renamed out of existence in build step 1.
	@Column(name = "show_id", nullable = false)
	private UUID showId;

	// BigDecimal maps to NUMERIC(19,2). Never double: binary floating point cannot hold 0.10
	// exactly, and both the simulated payment rule and the load test compare amounts exactly.
	@Column(nullable = false, precision = 19, scale = 2)
	private BigDecimal amount;

	// STRING, not ORDINAL. An ordinal stores the constant's POSITION, so inserting a new constant
	// into the middle of the enum silently changes the meaning of every row already written.
	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	private OrderStatus status;

	/**
	 * Optimistic lock. Hibernate appends {@code AND version = ?} to every update, so a writer working
	 * from a stale copy updates zero rows and is told it lost rather than overwriting the winner.
	 *
	 * <p>Nothing updates an order in this build step — the only transition is into {@code PENDING} at
	 * creation. The field exists now because adding it later means a migration plus backfilling a
	 * version onto every existing row, and because {@code OrderVersionIT} proves it works before
	 * anything depends on it.
	 */
	@Version
	private Long version;

	/**
	 * The requested seats, held in the {@code order_seats} table whose composite primary key makes a
	 * duplicate impossible in the database rather than only in validation.
	 *
	 * <p>TRADEOFF: fetched eagerly, which costs a join on every read of an order. Lazy loading would
	 * avoid that but would throw {@code LazyInitializationException} the moment a controller built a
	 * response after its transaction closed — the classic way this pattern goes wrong. An order has
	 * a handful of seats and is never read in bulk, so the join is not worth defending against.
	 */
	@ElementCollection(fetch = FetchType.EAGER)
	@CollectionTable(name = "order_seats", joinColumns = @JoinColumn(name = "order_id"))
	@Column(name = "seat_id", nullable = false, length = 32)
	private Set<String> seatIds = new LinkedHashSet<>();

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	@Column(name = "updated_at", nullable = false)
	private Instant updatedAt;

	/** Required by JPA, which instantiates entities reflectively. Not for application code. */
	protected Order() {
	}

	/**
	 * Creates a new order in {@link OrderStatus#PENDING}.
	 *
	 * <p>The caller supplies the id rather than letting the database generate one. WHY: the id is
	 * needed as a correlation key while the transaction is still open — the outbox row written
	 * alongside this order carries it — and waiting for a database-generated value would mean
	 * flushing mid-transaction to find out what the order is called.
	 */
	public Order(UUID id, UUID userId, UUID showId, Set<String> seatIds, BigDecimal amount) {
		this.id = Objects.requireNonNull(id, "id");
		this.userId = Objects.requireNonNull(userId, "userId");
		this.showId = Objects.requireNonNull(showId, "showId");
		this.amount = Objects.requireNonNull(amount, "amount");

		// Defensive copy. Without it the caller keeps a live reference into the aggregate's state and
		// can add a seat after the order has been validated.
		this.seatIds = new LinkedHashSet<>(Objects.requireNonNull(seatIds, "seatIds"));

		this.status = OrderStatus.PENDING;
	}

	/** The seats, as an unmodifiable view. Callers get to read them, not to reshape the order. */
	public Set<String> getSeatIds() {
		return java.util.Collections.unmodifiableSet(seatIds);
	}

	/**
	 * Moves the order to a new state.
	 *
	 * <p>No transition rules are enforced here yet, because no transition exists yet: build step 4
	 * introduces the move to {@code CONFIRMED} and step 5 the move to {@code CANCELLED}, and that is
	 * where the guards belong — written against real callers rather than imagined ones. It exists now
	 * so that {@code OrderVersionIT} has something to update, which is the only way to demonstrate
	 * that the version column actually detects a losing writer.
	 */
	public void changeStatus(OrderStatus next) {
		this.status = Objects.requireNonNull(next, "next");
	}

	// Both timestamps are set by the database's DEFAULT now() as well. The lifecycle callbacks exist
	// so the in-memory object matches the row without a re-read, which otherwise leaves createdAt
	// null on the instance the controller is about to serialize.
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
	 * Equality is by identifier, which is safe HERE and would not be on an entity whose id the
	 * database generates. The id is assigned in the constructor, before the object is ever persisted,
	 * so it never changes — meaning the hash code is stable and an {@code Order} can live in a
	 * {@code HashSet} across a {@code persist()} without being lost.
	 */
	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof Order that)) {
			return false;
		}
		return id.equals(that.id);
	}

	@Override
	public int hashCode() {
		return id.hashCode();
	}
}
