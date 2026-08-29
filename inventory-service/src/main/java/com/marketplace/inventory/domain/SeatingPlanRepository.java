package com.marketplace.inventory.domain;

import java.util.Collection;
import java.util.Set;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Persistence for the seating plan — {@link Show} and {@link ShowSeat} together, behind one
 * repository rather than two.
 *
 * <p>WHY one repository over two entities: the two refusal causes this service must be able to
 * produce that FR-033 names — a show that doesn't exist, and a seat label that doesn't exist within
 * a show that does — are two halves of a single existence check a booking decision performs once,
 * back to back. There is exactly one caller ({@code ReservationService}, arriving in a later task)
 * and it always asks both questions together. Splitting this into a {@code ShowRepository} and a
 * {@code ShowSeatRepository} would scatter one conceptual check across two files for no caller that
 * ever needs them separately — exactly the kind of split-for-its-own-sake the project constitution's
 * preference for demonstrated need over speculative structure argues against.
 *
 * <p>The generic type is {@link Show}, which is what supplies "does this show exist" for free via
 * the inherited {@code existsById}. The seat-label check below is a {@code @Query} method that reads
 * {@link ShowSeat} instead — Spring Data does not require a repository's queries to stay within the
 * entity named in its type parameter.
 */
public interface SeatingPlanRepository extends JpaRepository<Show, UUID> {

	/**
	 * Which of {@code seatLabels} actually exist within {@code showId}'s seating plan — in one query
	 * rather than one per label (FR-033).
	 *
	 * <p>The caller compares the size of what comes back against the size of what was asked for: if
	 * they differ, at least one requested label does not exist and the request is refused with
	 * {@code SEATS_NOT_FOUND} — deliberately distinct from {@code SEATS_ALREADY_HELD}, since one
	 * outcome never succeeds no matter how many times it is retried and the other very well might.
	 *
	 * <p>Answering with the existing subset rather than a boolean is what makes that comparison
	 * possible without a second query, and costs nothing extra to compute — the database has already
	 * done the matching to answer either question.
	 *
	 * @param showId     the show the seat labels are claimed to belong to
	 * @param seatLabels the labels a booking request named
	 * @return the subset of {@code seatLabels} that actually exist in {@code showId}'s plan
	 */
	@Query("""
			SELECT ss.id.seatLabel
			FROM   ShowSeat ss
			WHERE  ss.id.showId = :showId
			  AND  ss.id.seatLabel IN :seatLabels
			""")
	Set<String> findExistingSeatLabels(@Param("showId") UUID showId, @Param("seatLabels") Collection<String> seatLabels);
}
