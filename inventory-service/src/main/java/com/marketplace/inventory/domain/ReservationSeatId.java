package com.marketplace.inventory.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * The composite identity of one seat within one reservation: {@code (reservationId, seatLabel)},
 * matching {@code reservation_seats}' own composite primary key in
 * {@code V2__create_reservations.sql} exactly.
 *
 * <p>Not named in T127's own task description, which lists only {@code ReservationSeat.java} — added
 * here because a composite primary key needs somewhere to live, and this service already has one
 * precedent for that shape: {@link ShowSeatId} (T125). Matching that precedent — an
 * {@code @Embeddable} value class alongside its owning entity — keeps the two composite-key entities
 * in this service built the same way, rather than one using {@code @EmbeddedId} and the other
 * inventing a different mechanism for the identical problem.
 *
 * <p>Both fields are decided once, at the moment a seat is claimed, and never change afterward — so,
 * as with {@code ShowSeatId}, hand-written value-based {@code equals}/{@code hashCode} is exactly
 * what an {@code @EmbeddedId} class is required to provide.
 */
@Embeddable
public class ReservationSeatId implements Serializable {

	@Column(name = "reservation_id")
	private UUID reservationId;

	@Column(name = "seat_label", length = 16)
	private String seatLabel;

	/** Required by JPA. Not for application code. */
	protected ReservationSeatId() {
	}

	public ReservationSeatId(UUID reservationId, String seatLabel) {
		this.reservationId = Objects.requireNonNull(reservationId, "reservationId");
		this.seatLabel = Objects.requireNonNull(seatLabel, "seatLabel");
	}

	public UUID getReservationId() {
		return reservationId;
	}

	public String getSeatLabel() {
		return seatLabel;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof ReservationSeatId that)) {
			return false;
		}
		return reservationId.equals(that.reservationId) && seatLabel.equals(that.seatLabel);
	}

	@Override
	public int hashCode() {
		return Objects.hash(reservationId, seatLabel);
	}
}
