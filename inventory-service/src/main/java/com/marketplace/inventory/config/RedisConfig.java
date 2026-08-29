package com.marketplace.inventory.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * The Redis access this service's seat-lock mechanism uses.
 *
 * <p>Spring Boot already auto-configures a {@code StringRedisTemplate} bean from a
 * {@code RedisConnectionFactory} the moment {@code spring-boot-starter-data-redis} is on the
 * classpath, and it already applies {@code spring.data.redis.timeout} (T117 — 1 second) as the
 * client's command timeout when building that connection factory. Nothing here changes either of
 * those defaults, and this class does NOT construct its own {@code RedisConnectionFactory} — doing
 * so would risk silently losing that timeout the day someone edits the property and forgets a second,
 * hand-built factory exists to keep in sync with it.
 *
 * <p>WHY this class exists at all, then, given that Boot would provide the identical bean unasked:
 * the command timeout is not a convenience setting here, it is a correctness requirement the project
 * constitution names directly — Principle IV forbids an event handler from performing an
 * unbounded-latency operation inline in its critical path, and the Lua script evaluation this
 * template will run ({@code SeatLockStore}, a later task) is exactly that: a synchronous call, on
 * the thread deciding a real buyer's booking, to a process outside this JVM's control. Declaring the
 * bean explicitly, in a file whose only job is Redis, is what makes that requirement something a
 * reader finds by looking at this service's configuration classes rather than something they would
 * only discover by already knowing to check {@code application.yml} for a property with no code
 * pointing at it.
 *
 * <p>Verified directly rather than assumed: the actual wired {@code LettuceConnectionFactory}'s
 * {@code getClientConfiguration().getCommandTimeout()} reads back as exactly one second — reading the
 * live configuration object the connection factory was built with, not merely trusting that
 * {@code application.yml}'s property was spelled correctly. That distinction is not academic in this
 * build step: T117's own schema-configuration property looked correct in the file and was silently
 * ignored regardless.
 */
@Configuration
public class RedisConfig {

	@Bean
	public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
		return new StringRedisTemplate(connectionFactory);
	}
}
