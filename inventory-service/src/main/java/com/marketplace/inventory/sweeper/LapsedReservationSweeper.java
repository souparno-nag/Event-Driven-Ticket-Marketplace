package com.marketplace.inventory.sweeper;

import java.time.Instant;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.marketplace.inventory.domain.Reservation;
import com.marketplace.inventory.domain.ReservationRepository;
import com.marketplace.inventory.domain.ReservationSeat;
import com.marketplace.inventory.domain.ReservationSeatRepository;
import com.marketplace.inventory.domain.ReservationStatus;

/**
 * Retires lapsed reservations that nobody has contended for again, so expired-looking rows do not
 * accumulate indefinitely (FR-019).
 *
 * <p>Tidy-up, never load-bearing (research.md R6) — restated concretely rather than left as an
 * assertion: {@code ReservationService.decide(...)} already retires a lapsed reservation inline, the
 * moment anything next contends for its seats, in the very same transaction as the new booking
 * (FR-018). This class exists purely for the seats nobody ever asks for again — a hold on a show
 * nobody rebooks would otherwise sit `HELD`, with a lapse time in the past, forever, looking live to
 * anything that doesn't know to check the clock. {@code LapsedRebookingIT} (T149) is what proves
 * correctness never actually depends on this class having run: it disables the sweeper entirely and
 * shows rebooking still works on the first attempt regardless.
 *
 * <p>Deliberately not scoped to any one show — unlike the inline retirement in
 * {@code ReservationService}, which only ever looks at the seats one specific booking just asked
 * about, this sweep is service-wide: whatever is {@code HELD} and lapsed, anywhere, is fair game.
 */
@Component
public class LapsedReservationSweeper {

	private final ReservationRepository reservationRepository;
	private final ReservationSeatRepository reservationSeatRepository;
	private final boolean enabled;

	public LapsedReservationSweeper(
			ReservationRepository reservationRepository,
			ReservationSeatRepository reservationSeatRepository,
			@Value("${inventory.sweeper.enabled:true}") boolean enabled) {
		this.reservationRepository = reservationRepository;
		this.reservationSeatRepository = reservationSeatRepository;
		this.enabled = enabled;
	}

	/**
	 * WHY the enabled check lives inside the method rather than gating the {@code @Scheduled}
	 * annotation itself: Spring has no built-in way to make a fixed-delay schedule conditional on a
	 * property without a custom {@code Trigger} implementation, which would be considerably more
	 * machinery than this one boolean check needs. {@code LapsedRebookingIT} (T149) sets
	 * {@code inventory.sweeper.enabled=false} specifically so this method can be proven to do nothing
	 * while still being schedulable — the annotation stays active, the body simply declines to act.
	 */
	@Scheduled(fixedDelayString = "${inventory.sweeper.fixed-delay-ms:30000}")
	@Transactional
	public void sweep() {
		if (!enabled) {
			return;
		}

		Instant now = Instant.now();
		for (Reservation lapsed : reservationRepository.findByStatusAndLockExpiresAtBefore(ReservationStatus.HELD, now)) {
			lapsed.expire();
			for (ReservationSeat seat : reservationSeatRepository.findByIdReservationId(lapsed.getReservationId())) {
				seat.release(now);
			}
		}
	}
}
