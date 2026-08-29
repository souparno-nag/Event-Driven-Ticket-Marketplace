package com.marketplace.inventory.seats;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

import com.marketplace.inventory.InventoryIT;

/**
 * The nine guarantees {@code contracts/seat-lock-scripts.md} states for {@code lock_seats.lua} and
 * {@code release_seats.lua} — written and failing until {@code SeatLockScripts} (T153) exists to
 * autowire, and failing to pass even once it does until the developer exercise itself (T156) fills in
 * the two script bodies, which currently ship as empty stubs (T152). That two-stage failure is the
 * intended state: this file specifies the contract; T156 is where it starts being met.
 *
 * <p>Tests against the RAW scripts directly — {@code redisTemplate.execute(script, keys, args)} —
 * rather than through {@code SeatLockStore}'s higher-level boolean/count API. The contract itself is
 * phrased entirely in terms of {@code KEYS}, {@code ARGV}, and a numeric return value, and testing at
 * that exact level is what lets a guarantee be traced straight back to the four lines of Lua that
 * either satisfy it or don't, with nothing in between to blur which side of the boundary a failure
 * came from.
 *
 * <p>Guarantee 6 — under concurrent invocation for one seat, exactly one caller receives {@code 1} —
 * is deliberately NOT here. {@code ReservationContentionIT} (T144) is where genuine concurrency is
 * exercised, because the channel and a single-threaded test method both cap real parallelism far below
 * what actually distinguishes a correct all-or-nothing hold from a broken one that merely got lucky
 * (research.md R10).
 */
class SeatLockScriptIT extends InventoryIT {

	@Autowired
	@Qualifier("lockSeatsScript")
	RedisScript<Long> lockSeatsScript;

	@Autowired
	@Qualifier("releaseSeatsScript")
	RedisScript<Long> releaseSeatsScript;

	@Autowired
	StringRedisTemplate redisTemplate;

	private static final long TTL_MILLIS = Duration.ofSeconds(120).toMillis();

	// ---- lock_seats.lua ---------------------------------------------------------------------

	@Test
	void acquiresAllWhenAllFree() {
		UUID showId = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		List<String> keys = List.of(SeatKey.of(showId, "A1"), SeatKey.of(showId, "A2"), SeatKey.of(showId, "A3"));

		Long result = lock(keys, orderId);

		assertThat(result).isEqualTo(1L);
		for (String key : keys) {
			assertThat(redisTemplate.opsForValue().get(key)).isEqualTo(orderId.toString());
		}
	}

	@Test
	void acquiresNothingWhenAnyHeld() {
		UUID showId = UUID.randomUUID();
		UUID holder = UUID.randomUUID();
		UUID challenger = UUID.randomUUID();
		String held = SeatKey.of(showId, "B1");
		String free1 = SeatKey.of(showId, "B2");
		String free2 = SeatKey.of(showId, "B3");
		redisTemplate.opsForValue().set(held, holder.toString());

		Long result = lock(List.of(held, free1, free2), challenger);

		assertThat(result).isEqualTo(0L);
		// The trap this guarantee exists to catch: a script that sets keys as it goes would have
		// already taken free1 and/or free2 by the time it discovers held is unavailable. Both must
		// still be completely absent -- not merely "not owned by challenger", genuinely never set.
		assertThat(redisTemplate.opsForValue().get(free1)).isNull();
		assertThat(redisTemplate.opsForValue().get(free2)).isNull();
		assertThat(redisTemplate.opsForValue().get(held)).isEqualTo(holder.toString());
	}

	@Test
	void reacquiresOwnKeys() {
		// Simulates exactly the scenario the contract names as the reason this guarantee exists: a
		// retry after a transient error (a Postgres blip after the Redis call already succeeded, say)
		// finds the seats it locked microseconds earlier. Without this guarantee the retry would refuse
		// itself with SEATS_ALREADY_HELD for seats that are its own.
		UUID showId = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		List<String> keys = List.of(SeatKey.of(showId, "C1"), SeatKey.of(showId, "C2"));
		assertThat(lock(keys, orderId)).isEqualTo(1L);

		Long secondAttempt = lock(keys, orderId);

		assertThat(secondAttempt).isEqualTo(1L);
	}

	@Test
	void setsTtlOnEveryKey() {
		UUID showId = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		List<String> keys = List.of(SeatKey.of(showId, "D1"), SeatKey.of(showId, "D2"));

		lock(keys, orderId);

		for (String key : keys) {
			Long ttl = redisTemplate.getExpire(key, java.util.concurrent.TimeUnit.SECONDS);
			assertThat(ttl).isNotNull().isPositive().isLessThanOrEqualTo(120L);
		}
	}

	@Test
	void leavesOtherSeatsFree() {
		// A seat that exists but was never named in KEYS -- the script must not be able to touch
		// anything reachable only by, say, reconstructing a key from ARGV, which the contract
		// separately forbids (contracts/seat-lock-scripts.md, "Traps this contract exists to prevent").
		UUID showId = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		String requested = SeatKey.of(showId, "E1");
		String untouched = SeatKey.of(showId, "E2");

		lock(List.of(requested), orderId);

		assertThat(redisTemplate.opsForValue().get(untouched)).isNull();
	}

	// ---- release_seats.lua ------------------------------------------------------------------

	@Test
	void releasesOnlyOwnKeys() {
		UUID showId = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		List<String> keys = List.of(SeatKey.of(showId, "F1"), SeatKey.of(showId, "F2"));
		lock(keys, orderId);

		release(keys, orderId);

		for (String key : keys) {
			assertThat(redisTemplate.opsForValue().get(key)).isNull();
		}
	}

	@Test
	void doesNotStealAnotherOrdersSeat() {
		// The exact bug narrated in the contract's own walkthrough: order A's late release must never
		// delete a key order B has since legitimately acquired.
		UUID showId = UUID.randomUUID();
		UUID orderA = UUID.randomUUID();
		UUID orderB = UUID.randomUUID();
		String key = SeatKey.of(showId, "G1");
		redisTemplate.opsForValue().set(key, orderB.toString());

		release(List.of(key), orderA);

		assertThat(redisTemplate.opsForValue().get(key)).isEqualTo(orderB.toString());
	}

	@Test
	void releaseIsIdempotent() {
		UUID showId = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		String neverSet = SeatKey.of(showId, "H1");

		Long result = release(List.of(neverSet), orderId);

		assertThat(result).isEqualTo(0L);
	}

	@Test
	void reportsReleasedCount() {
		UUID showId = UUID.randomUUID();
		UUID orderId = UUID.randomUUID();
		List<String> keys = List.of(SeatKey.of(showId, "I1"), SeatKey.of(showId, "I2"), SeatKey.of(showId, "I3"));
		lock(keys, orderId);

		Long result = release(keys, orderId);

		assertThat(result).isEqualTo(3L);
	}

	private Long lock(List<String> keys, UUID orderId) {
		return redisTemplate.execute(lockSeatsScript, keys, orderId.toString(), String.valueOf(TTL_MILLIS));
	}

	private Long release(List<String> keys, UUID orderId) {
		return redisTemplate.execute(releaseSeatsScript, keys, orderId.toString());
	}
}
