package com.marketplace.inventory.domain;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for {@link Reservation}, plus the two lookups this service's correctness actually
 * depends on: which lapsed reservations a new booking must retire inline, and which live
 * reservations the startup rebuild must replay into Redis.
 */
public interface ReservationRepository extends JpaRepository<Reservation, UUID> {

	/**
	 * Finds the reservation belonging to a given order, if one exists.
	 *
	 * <p>Not the idempotency guard itself — {@code ProcessedMessage} (T129) is what actually decides
	 * whether a message has already been handled, checked before any hold is attempted (FR-032).
	 * This is the read side of the {@code order_id UNIQUE} constraint {@code V2__create_reservations.sql}
	 * declares — the shape a test or an operator uses to ask "what happened to this order's seats"
	 * without already knowing its reservation id.
	 */
	Optional<Reservation> findByOrderId(UUID orderId);

	/**
	 * Every reservation that is {@code HELD} and has not yet lapsed, as of {@code asOf} — exactly
	 * what the startup rebuild (a later task's {@code SeatLockRebuilder}) must replay into Redis
	 * before this service begins consuming booking requests (FR-015). Consuming first would judge a
	 * request against a store that has forgotten these holds, which is precisely the double-booking
	 * this ordering exists to prevent.
	 *
	 * <p>{@code asOf} is passed in rather than computed here so a test can rebuild against a fixed
	 * instant instead of racing the real clock.
	 */
	List<Reservation> findByStatusAndLockExpiresAtAfter(ReservationStatus status, Instant asOf);

	/**
	 * Every reservation that is {@code HELD} but HAS already lapsed, as of {@code asOf} — the
	 * opposite direction of {@link #findByStatusAndLockExpiresAtAfter}, and what
	 * {@code LapsedReservationSweeper} (T161) retires. Deliberately NOT scoped to any particular show
	 * or seat set, unlike {@link #findLapsedReservationsCoveringSeats}: the sweeper's job is tidying
	 * up whatever nobody has contended for again, service-wide, not reacting to one booking's own
	 * request.
	 */
	List<Reservation> findByStatusAndLockExpiresAtBefore(ReservationStatus status, Instant asOf);

	/**
	 * The reservations that currently hold any of {@code seatLabels} in {@code showId}, but whose
	 * hold has already lapsed as of {@code asOf} — the reservations a new booking contending for
	 * those same seats must retire inline, in the very same transaction as its own insert (FR-018).
	 *
	 * <p>Native SQL rather than JPQL, and joined by raw column equality rather than a mapped
	 * association, matching the same choice {@code OutboxRepository}'s {@code claimBatch} makes in
	 * order-service: {@link Reservation} and {@link ReservationSeat} deliberately carry no
	 * relationship to each other (see both entities' own Javadoc), so there is no association path
	 * for JPQL to walk, and a native join is the direct way to ask the question SQL is already
	 * answering for the live-seat constraint itself.
	 *
	 * <p>{@code SELECT DISTINCT r.*} — not {@code rs.*} — because the caller retires whole
	 * reservations: a single lapsed reservation covering three of the requested seats must appear
	 * once here, not three times.
	 *
	 * <p>WHY this lookup does not simply trust Redis's TTL having already freed the key: Redis frees
	 * a seat the instant its TTL lapses, but the PostgreSQL reservation is still {@code HELD} until
	 * something says otherwise. Without this query and the retirement it enables, the very next
	 * legitimate booking for that seat is rejected by {@code ux_reservation_seat_live} — the seats
	 * disagreeing in the opposite direction from the usual failure mode (research.md R6).
	 *
	 * @param showId     the show the requested seats belong to
	 * @param seatLabels the seat labels a new booking is requesting
	 * @param asOf       the instant against which "lapsed" is judged — passed in, not computed here,
	 *                   for the same testability reason as {@link #findByStatusAndLockExpiresAtAfter}
	 * @return the distinct reservations to retire before the new booking's own hold is taken
	 */
	@Query(value = """
			SELECT DISTINCT r.*
			FROM   reservations r
			JOIN   reservation_seats rs ON rs.reservation_id = r.reservation_id
			WHERE  rs.show_id = :showId
			  AND  rs.seat_label IN (:seatLabels)
			  AND  rs.released_at IS NULL
			  AND  r.lock_expires_at <= :asOf
			""", nativeQuery = true)
	List<Reservation> findLapsedReservationsCoveringSeats(
			@Param("showId") UUID showId,
			@Param("seatLabels") Collection<String> seatLabels,
			@Param("asOf") Instant asOf);
}
