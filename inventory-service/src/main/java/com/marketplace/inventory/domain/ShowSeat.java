package com.marketplace.inventory.domain;

import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * One seat label that exists within one show's seating plan.
 *
 * <p>Read-only from the application's point of view, for the same reason as {@link Show}: every row
 * is created by {@code V1__create_seating_plan.sql} at migration time, and nothing in this service
 * ever creates a show or a seat label at runtime (FR-033, FR-034). {@link SeatingPlanRepository}'s
 * only use of this entity is the existence check a booking decision needs.
 *
 * <p>Deliberately carries no relationship back to {@link Show} — no {@code @ManyToOne}. This
 * service does not navigate from a seat to its show as an object graph anywhere; it only ever asks
 * "does this (showId, seatLabel) pair exist", which {@link ShowSeatId} already answers on its own.
 * A mapped association here would buy nothing and would invite the lazy-loading pitfalls
 * {@code Order}'s own Javadoc in order-service warns about.
 */
@Entity
@Table(name = "show_seats")
@Getter
public class ShowSeat {

	@EmbeddedId
	private ShowSeatId id;

	/** Required by JPA. Not for application code. */
	protected ShowSeat() {
	}
}
