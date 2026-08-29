package com.marketplace.inventory.domain;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * One seat a reservation claims.
 *
 * <p>A child table rather than an array column or an {@code @ElementCollection} on
 * {@link Reservation}, for the same reason order-service gives each order's seats their own table:
 * the composite primary key ({@link ReservationSeatId}) makes a duplicate seat on one reservation
 * impossible IN THE DATABASE, and — unlike order-service's {@code order_seats}, which never changes
 * after insert — this table needs a per-seat mutable fact ({@link #releasedAt}) that an
 * {@code @ElementCollection} of plain strings could not carry at all.
 *
 * <p>Deliberately carries no relationship back to {@link Reservation} — no {@code @ManyToOne}, and
 * {@code reservationId} lives inside {@link ReservationSeatId} as a plain value rather than as a
 * mapped association. This service does not navigate from a seat to its reservation as an object
 * graph anywhere; the one query that needs both together ({@code ReservationRepository}'s lapsed-seat
 * lookup, T128) joins them by raw column equality in SQL, the same way order-service avoids
 * relationships between its own entities.
 */
@Entity
@Table(name = "reservation_seats")
@Getter
public class ReservationSeat {

	@EmbeddedId
	private ReservationSeatId id;

	/**
	 * Denormalised from the parent reservation. A partial unique index cannot reference a joined
	 * table, and the uniqueness {@code ux_reservation_seat_live} enforces is scoped PER SHOW, so this
	 * column has to live directly on this row. Written once at construction and never updated
	 * afterward, so it cannot drift from its parent ({@code data-model.md}).
	 */
	@Column(name = "show_id", nullable = false)
	private UUID showId;

	/**
	 * {@code NULL} while this seat is claimed (parent reservation {@code HELD} or {@code COMMITTED});
	 * set once the claim ends ({@code EXPIRED} or {@code RELEASED}). Deliberately NOT a duplicate of
	 * the parent's {@link ReservationStatus} — it answers a narrower question, "is this seat claimed
	 * right now", which is true for two different parent statuses and false for the other two. This
	 * field is what {@code ux_reservation_seat_live} actually reads (research.md R5).
	 */
	@Column(name = "released_at")
	private Instant releasedAt;

	/** Required by JPA. Not for application code. */
	protected ReservationSeat() {
	}

	public ReservationSeat(UUID reservationId, String seatLabel, UUID showId) {
		this.id = new ReservationSeatId(reservationId, seatLabel);
		this.showId = Objects.requireNonNull(showId, "showId");
	}

	/**
	 * Marks this seat as no longer claimed, moving it out of {@code ux_reservation_seat_live}'s scope
	 * and freeing the {@code (showId, seatLabel)} pair for a new live claim.
	 *
	 * <p>Called from exactly one place — {@code ReservationService}'s inline retirement of a lapsed
	 * reservation contending for this seat (FR-018) — so there is exactly one method to verify
	 * against the mapping table in {@code V2__create_reservations.sql}'s own comments.
	 */
	public void release(Instant when) {
		this.releasedAt = Objects.requireNonNull(when, "when");
	}
}
