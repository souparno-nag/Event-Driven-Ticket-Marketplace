package com.marketplace.inventory;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Base class for the tests in this build step that drive genuine hundreds-to-thousands-way
 * concurrency directly at {@code ReservationService} — {@code ReservationContentionIT},
 * {@code ReservationPartialOverlapIT}, {@code ReservationDisjointIT}, and
 * {@code ReservationVersionIT}.
 *
 * <p>WHY these four need a different pool from every other test in this service: {@link InventoryIT}
 * shrinks the shared pool to 5, for the identical reason order-service's own {@code PostgresIT} does —
 * several distinct, simultaneously-cached Spring test contexts each eagerly open their own full pool,
 * and the sum across all of them must stay comfortably under what a single Testcontainers PostgreSQL
 * instance allows. That reasoning holds for ordinary tests making a handful of sequential calls. It
 * does not hold here: these four tests hold open a genuinely concurrent transaction PER VIRTUAL
 * THREAD, and {@code ReservationContentionIT} alone runs a THOUSAND of them at once, twenty times
 * over. Discovered directly, not assumed: with the shared pool of 5, every one of these tests failed
 * with {@code SQLTransientConnectionException}, not because anything about the seat-locking logic
 * was wrong, but because a thousand-way burst against five connections is pool exhaustion by
 * construction, regardless of how correct the code under test is.
 *
 * <p>WHY this sets {@link InventoryIT#poolSize} in a static initialiser rather than registering its
 * own {@code @DynamicPropertySource} for the same key: tried first, and confirmed NOT to work — a
 * probe test showed the effective pool size stayed at 5 regardless, because Spring does not let a
 * subclass's registration for an already-registered key win over the ancestor's, the same behaviour
 * order-service's own {@code PostgresIT} documents. {@code poolSize} is a mutable field
 * {@code InventoryIT}'s own supplier reads lazily, at the moment Spring actually evaluates it — set
 * this field before that point, in a static initialiser the JVM runs the moment this class loads,
 * which is well before Spring ever builds the context, and there is only ever one registration to
 * begin with.
 *
 * <p>{@code connection-timeout} is raised too, from the production value of 250ms to 5 seconds, for a
 * parallel reason: 250ms is this service's own admission-control bound for a single real request under
 * ordinary load (R12) — exactly the wrong yardstick for "how long is it reasonable to wait for one of
 * sixty connections when a thousand callers are deliberately contending for them at once," which is
 * what these tests exist to create on purpose. Raising it here recognises that these four tests are
 * measuring something the 250ms production bound was never designed to be measured against.
 *
 * <p>The pool size chosen — 60 — is a deliberate middle point: large enough that a thousand-way burst
 * drains without spurious connection-acquisition failures standing in for the actual result, and small
 * enough that, added to whatever other cached test contexts might still be alive in the same JVM, the
 * total stays well under a fresh Testcontainers PostgreSQL instance's own default connection ceiling —
 * a real ceiling checked directly against this image ({@code postgres:16-alpine}'s own default
 * {@code max_connections}, 100), not assumed.
 *
 * <p>{@code @DirtiesContext(classMode = AFTER_CLASS)} is inherited from {@link InventoryIT} itself
 * rather than redeclared here — see that class's own Javadoc for why the first version of this fix,
 * scoped only to this one base class, still was not enough once the whole suite ran together rather
 * than one class at a time in isolation.
 */
public abstract class HighConcurrencyIT extends InventoryIT {

	static {
		poolSize = 60;
	}

	@DynamicPropertySource
	static void widenConnectionTimeoutForGenuineConcurrency(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.hikari.connection-timeout", () -> "5000");
	}
}
