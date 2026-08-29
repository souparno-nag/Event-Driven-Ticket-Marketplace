package com.marketplace.inventory.domain;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Persistence for {@link ReservationSeat}.
 *
 * <p>Not requested by this build step's own task list, but needed by {@code ReservationService}
 * (T160): retiring a lapsed reservation means releasing every seat it covers, and doing that as a
 * normal, tracked entity mutation — {@code seat.release(when)}, relying on JPA's own dirty checking
 * to persist it, exactly the way {@link Reservation#expire()} is already mutated and persisted —
 * needs somewhere to load those rows from first. A bulk {@code UPDATE ... WHERE reservation_id = ?}
 * would avoid needing this repository at all, but it was rejected here specifically because it would
 * make releasing a reservation's seats a differently-shaped operation from expiring the reservation
 * itself, for no reason connected to either operation's own logic — the two should read as the same
 * kind of change because they are one.
 *
 * <p>{@code findByIdReservationId} reaches through {@link ReservationSeatId}'s own field by Spring
 * Data's standard nested-property naming — {@code id.reservationId} — rather than needing a
 * hand-written {@code @Query}.
 */
public interface ReservationSeatRepository extends JpaRepository<ReservationSeat, ReservationSeatId> {

	List<ReservationSeat> findByIdReservationId(UUID reservationId);
}
