package com.marketplace.inventory.startup;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import com.marketplace.inventory.InventoryIT;
import com.marketplace.inventory.InventoryServiceApplication;
import com.marketplace.inventory.SeatingPlanFixture;

/**
 * SC-013/SC-014: a hold recorded in PostgreSQL survives Redis losing its memory entirely — the exact
 * situation {@code infra/docker-compose.yml}'s deliberate {@code --save ""} produces on every restart
 * (research.md R4) — and is back in Redis, at its ORIGINAL expiry rather than a fresh one, before
 * anything is allowed to consume a booking request.
 *
 * <p>WHY this test builds a SECOND, independent Spring context inside the test method rather than
 * simply restarting the one {@link InventoryIT} already manages: the whole point is to observe
 * something that happens once, at startup, before any other bean is available for use — the rebuild.
 * {@code @SpringBootTest}'s own context is already fully started by the time a test method runs, so
 * there is no "before consumption starts" moment left to observe inside it. A fresh
 * {@link SpringApplicationBuilder} run, pointed at the SAME PostgreSQL and Redis this test's own
 * inherited context already uses, is what lets this test watch a real startup happen and inspect
 * Redis the instant that startup finishes — which is exactly the window SC-013 makes a claim about.
 *
 * <p>WHY that second context cannot be built by extending {@link InventoryIT} a second time or by
 * registering a second {@code @DynamicPropertySource}: verified directly by this session's own earlier
 * work on {@code HighConcurrencyIT} — a subclass registering the same property key as an ancestor does
 * not win, Spring keeps the ancestor's registration regardless of which subclass runs later. Building
 * the second context by hand, with an explicit {@link Properties} map read from the very same
 * {@code POSTGRES}/{@code REDIS} containers {@link InventoryIT} already exposes, sidesteps that
 * limitation entirely rather than fighting it.
 *
 * <p>Expected to fail until {@code SeatLockRebuilder} (T179) exists: nothing currently repopulates
 * Redis on startup at all, so the restored key will not exist and the TTL assertion has nothing to
 * read.
 */
class SeatLockRebuildIT extends InventoryIT {

	/**
	 * A Kafka broker of this test's own, used by nothing else. {@code InventoryIT} deliberately
	 * carries no Kafka container at all (see its own Javadoc), so the SECOND, manually-built
	 * application this test starts — which restores {@code auto-startup} and genuinely starts its
	 * {@code @KafkaListener} — would otherwise have nothing to point at but {@code application.yml}'s
	 * own real-environment default, {@code localhost:9092}. Bringing up a disposable broker here,
	 * exactly the way {@code UndecidableRequestIT} brings up its own private infrastructure for a
	 * parallel reason, is what keeps this test from ever touching a Kafka broker outside its own
	 * control. Left for Testcontainers' own Ryuk companion to remove on JVM exit, matching
	 * {@code InventoryIT}'s own stated reasoning for every other container in this service's suite:
	 * hand-written cleanup reliably fails to run on a crash, and Ryuk does not.
	 */
	private static final KafkaContainer KAFKA =
			new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.7.1"));

	static {
		KAFKA.start();
	}

	@Autowired
	JdbcTemplate jdbcTemplate;

	@Autowired
	StringRedisTemplate redisTemplate;

	@Autowired
	@Qualifier("lockSeatsScript")
	RedisScript<Long> lockSeatsScript;

	@Test
	void rebuildPrecedesConsumptionAndPreservesTheOriginalExpiry() throws Exception {
		var show = SeatingPlanFixture.provisionShow(jdbcTemplate, "SeatLockRebuild", 1);
		String seat = show.seatLabels().get(0);
		UUID reservationId = UUID.randomUUID();
		UUID holderOrderId = UUID.randomUUID();

		// A hold that is genuinely still live -- 60 seconds of its 120-second lifetime still ahead of
		// it, unlike LapsedReservationFixture's own already-expired rows, because this test needs a
		// hold the rebuild is supposed to PRESERVE, not one it is supposed to retire.
		Instant lockExpiresAt = Instant.now().plusSeconds(60);
		jdbcTemplate.update("""
				INSERT INTO reservations (reservation_id, order_id, show_id, status, lock_expires_at)
				VALUES (?, ?, ?, 'HELD', ?)
				""", reservationId, holderOrderId, show.showId(), Timestamp.from(lockExpiresAt));
		jdbcTemplate.update("""
				INSERT INTO reservation_seats (reservation_id, seat_label, show_id)
				VALUES (?, ?, ?)
				""", reservationId, seat, show.showId());

		// Simulates the hold's own key already being in Redis before the outage -- what
		// docker-compose's --save "" actually loses is exactly this key, not the PostgreSQL row.
		String redisKey = com.marketplace.inventory.seats.SeatKey.of(show.showId(), seat);
		redisTemplate.opsForValue().set(redisKey, holderOrderId.toString(), 60, TimeUnit.SECONDS);

		// The outage itself: every key in Redis, gone -- indistinguishable from what a restart with
		// snapshotting disabled produces.
		redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<Object>) connection -> {
			connection.serverCommands().flushAll();
			return null;
		});
		assertThat(redisTemplate.hasKey(redisKey)).as("the outage genuinely removed the key").isFalse();

		// The "restart": a second, independent application context, pointed at the SAME PostgreSQL
		// and Redis this test's own inherited context already uses.
		//
		// Passed as COMMAND-LINE-shaped arguments to run(...), not via SpringApplicationBuilder's own
		// properties(Properties) method. Found necessary directly, not assumed: properties(...) adds
		// them as Spring Boot's own "default properties" source, which sits at the LOWEST precedence
		// in the property resolution order -- application.yml's own committed, real-environment values
		// (localhost:5432, localhost:6379, localhost:9092) still won, silently. The first run of this
		// test genuinely connected its "restarted" application to whatever real PostgreSQL, Redis, and
		// Kafka happen to be running on their default local ports, not to this test's own Testcontainers
		// instances -- confirmed by the log line showing the consumer joining a group against
		// localhost:9092. Command-line arguments sit at the HIGHEST precedence, above application.yml,
		// which is what actually makes this override take effect.
		String[] restartArgs = {
				"--spring.datasource.url=" + jdbcUrlWithSchemaForRebuild(),
				"--spring.datasource.username=" + POSTGRES.getUsername(),
				"--spring.datasource.password=" + POSTGRES.getPassword(),
				"--spring.data.redis.host=" + REDIS.getHost(),
				"--spring.data.redis.port=" + REDIS.getMappedPort(6379),
				"--spring.kafka.bootstrap-servers=" + KAFKA.getBootstrapServers(),
		};

		try (ConfigurableApplicationContext restarted = new SpringApplicationBuilder(InventoryServiceApplication.class)
				.run(restartArgs)) {

			// The instant startup finishes is the instant SC-013 makes its claim about: the hold must
			// already be observable in Redis, with less than a full 120-second lifetime left on it --
			// proof the rebuild used the reservation's OWN lock_expires_at (PXAT, an absolute
			// instant), not a fresh 120-second TTL that would silently extend every in-flight hold on
			// every restart (FR-016).
			Long ttlSeconds = redisTemplate.getExpire(redisKey, TimeUnit.SECONDS);
			assertThat(ttlSeconds).as("the restored key exists with the original expiry preserved")
					.isNotNull().isPositive().isLessThan(120L);

			// A competing request for the same seat, evaluated directly against the real Lua script
			// this service actually uses, must be refused -- the hold the rebuild just restored is
			// not a decoration, it is genuinely enforced the moment startup completes.
			Long competingAttempt = redisTemplate.execute(lockSeatsScript,
					List.of(redisKey), UUID.randomUUID().toString(), "120000");
			assertThat(competingAttempt).as("a competing hold on the restored seat must be refused").isEqualTo(0L);
		}
	}

	/**
	 * Duplicates {@link InventoryIT}'s own private {@code jdbcUrlWithSchema()} rather than promoting
	 * that method's visibility just for this one caller -- the method is three lines, used from
	 * exactly one place outside its own class, and widening a private helper's visibility for a
	 * single external caller is a larger, more permanent change to {@code InventoryIT} than repeating
	 * three lines here.
	 */
	private static String jdbcUrlWithSchemaForRebuild() {
		String url = POSTGRES.getJdbcUrl();
		return url + (url.contains("?") ? "&" : "?") + "currentSchema=inventory";
	}
}
