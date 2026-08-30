package com.marketplace.inventory.startup;

import java.time.Instant;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.connection.RedisStringCommands.SetOption;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.types.Expiration;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.stereotype.Component;

import com.marketplace.inventory.domain.Reservation;
import com.marketplace.inventory.domain.ReservationRepository;
import com.marketplace.inventory.domain.ReservationSeat;
import com.marketplace.inventory.domain.ReservationSeatRepository;
import com.marketplace.inventory.domain.ReservationStatus;
import com.marketplace.inventory.seats.SeatKey;

/**
 * Replays every live hold from PostgreSQL into Redis, then — and only then — starts this service's
 * Kafka listeners (research.md R4; FR-015, FR-016; contracts/inventory-consumer.md guarantee 10).
 *
 * <p>WHY Redis needs rebuilding at all: {@code infra/docker-compose.yml} runs it with {@code --save
 * ""} — snapshotting deliberately disabled, because Redis here is a cache, not a system of record
 * (data-model.md). Every restart forgets every hold it was tracking. Without this class, the first
 * booking request after any restart would be judged against a store that has forgotten every existing
 * hold that is still genuinely live in PostgreSQL — a double-booking with no error raised anywhere,
 * because as far as Redis is concerned the seat was simply always free.
 *
 * <p>WHY an {@link ApplicationRunner} rather than {@code @PostConstruct} or an
 * {@code ApplicationReadyEvent} listener — both considered and rejected in research.md R4:
 * {@code @PostConstruct} runs during bean initialisation, before Spring guarantees the datasource (or
 * anything else this class depends on) is actually ready in every possible startup ordering.
 * {@code ApplicationReadyEvent} fires AFTER the application is fully up — which, for a
 * {@code @KafkaListener}, means after the listener containers have already started, precisely
 * backwards from what this class needs. An {@code ApplicationRunner} runs once, after the context is
 * fully refreshed and every bean is ready, but before {@code SpringApplication.run(...)} returns to
 * its caller — the one point in the startup sequence where "everything is ready, but nothing has
 * started consuming yet" is actually true.
 *
 * <p>WHY {@code spring.kafka.listener.auto-startup: false} matters as much as this class's own code:
 * Spring Kafka would otherwise start every {@code @KafkaListener} container automatically the moment
 * the context finishes refreshing — including this runner's OWN moment to act, racing it rather than
 * waiting for it. Turning auto-start off is what carves out the gap this class fills; starting the
 * registry explicitly, at the end of this method, is what closes it again.
 *
 * <p>WHY {@code inventory.rebuild.enabled} exists at all — found necessary, not designed in up
 * front, matching {@code LapsedReservationSweeper}'s own identical flag: every test in this service
 * boots the FULL application context, this class included, regardless of whether that particular test
 * has anything to do with Kafka. Left ungated, this class would start a real {@code @KafkaListener}
 * against WHATEVER {@code spring.kafka.bootstrap-servers} happens to resolve to for that test's own
 * context — the real, local, production-shaped broker for any test that never overrides it — and that
 * consumer joining and leaving the same real consumer group, repeatedly, across dozens of unrelated
 * test classes, was confirmed directly to be what was intermittently starving an entirely different,
 * Kafka-using test's own consumer of the CPU it needed to get scheduled within its own timeout window.
 * Defaulting to {@code true} keeps production and any test that genuinely needs real consumption
 * (which sets this explicitly) working exactly as before; {@code InventoryIT} disables it for the
 * large majority of tests that have no business touching Kafka at all.
 */
@Component
public class SeatLockRebuilder implements ApplicationRunner {

	private final ReservationRepository reservationRepository;
	private final ReservationSeatRepository reservationSeatRepository;
	private final StringRedisTemplate redisTemplate;
	private final KafkaListenerEndpointRegistry listenerRegistry;
	private final boolean enabled;

	public SeatLockRebuilder(
			ReservationRepository reservationRepository,
			ReservationSeatRepository reservationSeatRepository,
			StringRedisTemplate redisTemplate,
			KafkaListenerEndpointRegistry listenerRegistry,
			@Value("${inventory.rebuild.enabled:true}") boolean enabled) {
		this.reservationRepository = reservationRepository;
		this.reservationSeatRepository = reservationSeatRepository;
		this.redisTemplate = redisTemplate;
		this.listenerRegistry = listenerRegistry;
		this.enabled = enabled;
	}

	@Override
	public void run(ApplicationArguments args) {
		if (!enabled) {
			return;
		}

		Instant now = Instant.now();

		for (Reservation held : reservationRepository.findByStatusAndLockExpiresAtAfter(ReservationStatus.HELD, now)) {
			for (ReservationSeat seat : reservationSeatRepository.findByIdReservationId(held.getReservationId())) {
				// A reservation's seats are all released together the moment it is retired (FR-018) --
				// findByStatusAndLockExpiresAtAfter already filters to HELD reservations, so a released
				// seat row here would mean a data inconsistency this rebuild should not paper over by
				// silently re-locking it. In practice every seat under a HELD reservation is live; this
				// check is what keeps that an enforced fact rather than an assumption.
				if (seat.getReleasedAt() == null) {
					restoreHold(held, seat);
				}
			}
		}

		// Only now: every hold PostgreSQL still considers live is back in Redis, so a booking request
		// arriving the instant listeners start sees the true state, not a store still catching up.
		listenerRegistry.getListenerContainers().forEach(container -> {
			if (!container.isRunning()) {
				container.start();
			}
		});
	}

	/**
	 * One {@code SET key value PXAT <epoch millis>} — a single atomic command, not a {@code SET}
	 * followed by a separate {@code EXPIREAT}, so there is no window between the two where the key
	 * exists with no expiry at all.
	 *
	 * <p>{@code PXAT} sets an ABSOLUTE expiry rather than a fresh duration (FR-016). Using a relative
	 * TTL here — {@code EX 120}, say — would silently hand every hold surviving a restart a brand new
	 * 120-second lifetime measured from the moment of the restart, regardless of how much of its
	 * original hold had already elapsed. That would let a hold outlive what {@code SeatsReserved}
	 * already announced to the rest of the saga as its {@code lockExpiresAt}, and a fencing check
	 * trusting that announcement would disagree with what Redis still believes.
	 */
	private void restoreHold(Reservation held, ReservationSeat seat) {
		String key = SeatKey.of(held.getShowId(), seat.getId().getSeatLabel());
		byte[] keyBytes = redisTemplate.getStringSerializer().serialize(key);
		byte[] valueBytes = redisTemplate.getStringSerializer().serialize(held.getOrderId().toString());
		long expiresAtEpochMillis = held.getLockExpiresAt().toEpochMilli();

		redisTemplate.execute((RedisCallback<Boolean>) connection -> connection.stringCommands()
				.set(keyBytes, valueBytes, Expiration.unixTimestamp(expiresAtEpochMillis, TimeUnit.MILLISECONDS),
						SetOption.upsert()));
	}
}
