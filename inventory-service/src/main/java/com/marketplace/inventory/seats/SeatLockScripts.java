package com.marketplace.inventory.seats;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.script.DefaultRedisScript;

/**
 * Loads the two Lua scripts as Spring beans, so the rest of this service depends on a
 * {@code DefaultRedisScript<Long>} rather than on a file path scattered through call sites.
 *
 * <p>Bean names — {@code lockSeatsScript} and {@code releaseSeatsScript} — are load-bearing, not
 * cosmetic: {@code SeatLockStore} (T154) and every test in {@code SeatLockScriptIT} (T143) inject
 * these by exactly these names via {@code @Qualifier}, since {@code RedisScript<Long>} alone is not a
 * unique type — both scripts share it.
 *
 * <p>Both scripts currently ship as empty files carrying only a header comment stating their
 * contract ({@code src/main/resources/scripts/*.lua}, T152) — the developer exercise this build step
 * actually asks for (research.md R11). This class, the beans it declares, and everything that calls
 * them are complete and working regardless; only the two script BODIES are still to be written.
 */
@Configuration
public class SeatLockScripts {

	@Bean
	public DefaultRedisScript<Long> lockSeatsScript() {
		return script("scripts/lock_seats.lua");
	}

	@Bean
	public DefaultRedisScript<Long> releaseSeatsScript() {
		return script("scripts/release_seats.lua");
	}

	/**
	 * WHY the result type is {@code Long}, not {@code Boolean} or {@code String}: Lua's {@code false}
	 * converts to a Redis nil reply on the wire, which has no sensible mapping back to a Java
	 * {@code Boolean} — Spring Data Redis would either fail the conversion or silently hand back
	 * {@code null}, either of which turns "the hold failed" into a stack trace or a
	 * {@code NullPointerException} somewhere downstream rather than the numeric {@code 0} both
	 * scripts' own contracts specify. Declaring the type here, once, is what makes returning a
	 * boolean from either script body a compile-time-adjacent mistake instead of a runtime surprise
	 * discovered under load.
	 */
	private static DefaultRedisScript<Long> script(String classpathLocation) {
		DefaultRedisScript<Long> script = new DefaultRedisScript<>();
		script.setLocation(new ClassPathResource(classpathLocation));
		script.setResultType(Long.class);
		return script;
	}
}
