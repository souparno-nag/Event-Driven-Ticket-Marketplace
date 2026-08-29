package com.marketplace.inventory.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;

/**
 * A performance that can be booked, and the owner of a seating plan.
 *
 * <p>Unlike {@link Reservation}, this entity is effectively read-only from the application's point
 * of view. Every row is created by {@code V1__create_seating_plan.sql} at migration time — there is
 * deliberately no HTTP surface anywhere in this service that creates a show (FR-033, FR-034;
 * `research.md` R14), so nothing in application code ever calls {@code save()} against one. The
 * service only ever asks two questions of this table: does a show with this id exist, and — via
 * {@link ShowSeat} — do these seat labels exist within it.
 *
 * <p>That read-only nature is why {@code show_id} carries no {@code @GeneratedValue}: the database's
 * own {@code DEFAULT gen_random_uuid()} assigns it once, at seed time, and Hibernate never needs to
 * read a generated value back because it never inserts a row here.
 */
@Entity
@Table(name = "shows")
@Getter
public class Show {

	@Id
	@Column(name = "show_id")
	private UUID showId;

	/** For diagnosis only. Nothing branches on this value — a request identifies a show by its id. */
	@Column(nullable = false, length = 200)
	private String name;

	@Column(name = "created_at", nullable = false)
	private Instant createdAt;

	/** Required by JPA, which instantiates entities reflectively. Not for application code. */
	protected Show() {
	}
}
