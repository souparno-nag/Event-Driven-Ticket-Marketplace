package com.marketplace.orders.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;

import com.marketplace.orders.PostgresIT;
import com.zaxxer.hikari.HikariDataSource;

/**
 * Specifies overload behaviour: when the connection pool is saturated, excess requests are refused
 * fast with 503 rather than queuing behind a slow default (FR-035, FR-036, SC-016).
 *
 * <p>Re-declares {@code @SpringBootTest} with {@code RANDOM_PORT}, replacing {@link PostgresIT}'s
 * inherited mock-web-environment for this class only — a real HTTP round trip against a genuinely
 * exhausted pool is the only way to observe this behaviour; MockMvc's synchronous, single-threaded
 * dispatch would not exhibit real connection contention.
 *
 * <p>WHY the pool size is NOT shrunk via a property override, which was the first thing tried here:
 * Spring Boot's own startup needs more than one connection in flight at once — Flyway's migration
 * check and Hibernate's own validation can overlap — and a pool of exactly 1 or 2 made the
 * application fail to start at all, before any test method ran, with an error that reads like a
 * broken test rather than the saturation this test means to observe. Holding EVERY connection the
 * real, unmodified pool has (read from the running {@link HikariDataSource} rather than
 * hard-coded, so it always matches {@code application.yml}) sidesteps that: startup completes
 * normally against the full pool, and only this test's own act of holding all of it creates the
 * saturation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OrderCapacityIT extends PostgresIT {

	@LocalServerPort
	private int port;

	@Autowired
	private DataSource dataSource;

	@Test
	void excessRequestsAreRefusedWith503WhenThePoolIsSaturated() throws Exception {
		int poolSize = ((HikariDataSource) dataSource).getMaximumPoolSize();
		List<Connection> held = new ArrayList<>();

		try {
			for (int i = 0; i < poolSize; i++) {
				held.add(dataSource.getConnection());
			}

			HttpClient client = HttpClient.newHttpClient();
			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create("http://localhost:" + port + "/api/orders"))
					.timeout(Duration.ofSeconds(5))
					.header("Content-Type", "application/json")
					.POST(HttpRequest.BodyPublishers.ofString(validBody()))
					.build();

			HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

			// The refusal must be 503 specifically -- never the 400 given to a malformed request, and
			// never a bare 500, both of which would hide overload behind an unrelated-looking failure.
			assertThat(response.statusCode()).isEqualTo(503);
			assertThat(response.headers().firstValue("Retry-After")).isPresent();

			String body = response.body();
			assertThat(body).contains("capacity");
			assertThat(body).doesNotContain("validation-failed");
		} finally {
			for (Connection connection : held) {
				connection.close();
			}
		}
	}

	private static String validBody() {
		return """
				{"userId":"%s","showId":"%s","seatIds":["A1"],"amount":"10.00"}
				""".formatted(UUID.randomUUID(), UUID.randomUUID());
	}
}
