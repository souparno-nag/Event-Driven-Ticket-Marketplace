package com.marketplace.inventory.domain;

import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * The composite identity of one seat label within one show: {@code (showId, seatLabel)}.
 *
 * <p>A seat label is meaningful only relative to its show — "A1" in one show and "A1" in another are
 * two entirely different seats — so this key is the two fields together, matching
 * {@code show_seats}' own composite primary key in {@code V1__create_seating_plan.sql} exactly.
 *
 * <p>Unlike {@code Order}'s own identity in order-service, this is a genuine value object rather
 * than a database-generated surrogate: both fields are decided by the seed migration and never
 * change afterward, so hand-writing {@code equals}/{@code hashCode} from the field values — rather
 * than the identity-based approach used for an entity whose id is assigned once and never recomputed
 * — is exactly what an {@code @EmbeddedId} class is required to do.
 */
@Embeddable
public class ShowSeatId implements Serializable {

	@Column(name = "show_id")
	private UUID showId;

	@Column(name = "seat_label", length = 16)
	private String seatLabel;

	/** Required by JPA. Not for application code. */
	protected ShowSeatId() {
	}

	public ShowSeatId(UUID showId, String seatLabel) {
		this.showId = Objects.requireNonNull(showId, "showId");
		this.seatLabel = Objects.requireNonNull(seatLabel, "seatLabel");
	}

	public UUID getShowId() {
		return showId;
	}

	public String getSeatLabel() {
		return seatLabel;
	}

	@Override
	public boolean equals(Object other) {
		if (this == other) {
			return true;
		}
		if (!(other instanceof ShowSeatId that)) {
			return false;
		}
		return showId.equals(that.showId) && seatLabel.equals(that.seatLabel);
	}

	@Override
	public int hashCode() {
		return Objects.hash(showId, seatLabel);
	}
}
