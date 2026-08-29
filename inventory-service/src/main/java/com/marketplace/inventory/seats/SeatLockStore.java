package com.marketplace.inventory.seats;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

/**
 * The one place this service actually talks to Redis for contention: builds the keys, evaluates the
 * script, and translates the numeric result the contract specifies into the boolean or count outcome
 * a caller actually wants.
 *
 * <p>TRADEOFF: {@link #tryLock} is called from inside {@code ReservationService}'s transaction, but
 * it is not itself transactional — Redis has no participation in a JDBC rollback. If the surrounding
 * transaction fails AFTER this call has already succeeded, the seats stay held in Redis until their
 * TTL lapses on its own; nobody frees them early, and nothing tells them to. That is the accepted
 * direction of failure (contracts/inventory-consumer.md): seats briefly unavailable to everyone,
 * never double-sold to two people. The reverse ordering — writing the database first, evaluating the
 * script second — was rejected because it fails the other way: a script failure after a committed
 * database write would leave a reservation recorded with no hold behind it, which is the actual
 * failure mode this service exists to prevent, not merely an inconvenient one.
 */
@Component
public class SeatLockStore {

	private final StringRedisTemplate redisTemplate;
	private final RedisScript<Long> lockSeatsScript;
	private final RedisScript<Long> releaseSeatsScript;
	private final long ttlMillis;

	public SeatLockStore(
			StringRedisTemplate redisTemplate,
			@Qualifier("lockSeatsScript") RedisScript<Long> lockSeatsScript,
			@Qualifier("releaseSeatsScript") RedisScript<Long> releaseSeatsScript,
			@Value("${inventory.hold.ttl-ms:120000}") long ttlMillis) {
		this.redisTemplate = redisTemplate;
		this.lockSeatsScript = lockSeatsScript;
		this.releaseSeatsScript = releaseSeatsScript;
		this.ttlMillis = ttlMillis;
	}

	/**
	 * Attempts to hold every seat in {@code seatIds}, all at once, for {@code orderId}.
	 *
	 * @return {@code true} only if every requested seat was free (or already held by this same
	 *         order — the self-owned case a retry needs, guarantee 3 of
	 *         {@code contracts/seat-lock-scripts.md}) and is now held for the configured TTL;
	 *         {@code false} if any one of them was held by someone else, in which case NONE of them
	 *         were taken
	 */
	public boolean tryLock(UUID showId, Collection<String> seatIds, UUID orderId) {
		List<String> keys = keysFor(showId, seatIds);
		Long result = redisTemplate.execute(lockSeatsScript, keys, orderId.toString(), String.valueOf(ttlMillis));
		return result != null && result == 1L;
	}

	/**
	 * Releases every seat in {@code seatIds} currently held by {@code orderId}, leaving a seat held
	 * by any other order — or already free — completely untouched.
	 *
	 * <p>Not called from anywhere in this build step: the {@code OrderCancelled} message that would
	 * trigger a release has no publisher until step 5 (spec.md Assumptions). Wired up now so that step
	 * fills in a caller rather than designs this method from nothing.
	 *
	 * @return the number of seats actually released
	 */
	public long release(UUID showId, Collection<String> seatIds, UUID orderId) {
		List<String> keys = keysFor(showId, seatIds);
		Long result = redisTemplate.execute(releaseSeatsScript, keys, orderId.toString());
		return result == null ? 0L : result;
	}

	private static List<String> keysFor(UUID showId, Collection<String> seatIds) {
		return seatIds.stream().map(seatId -> SeatKey.of(showId, seatId)).toList();
	}
}
