package com.marketplace.orders;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import com.marketplace.orders.domain.Order;
import com.marketplace.orders.outbox.OutboxRecord;

import jakarta.persistence.EntityManagerFactory;

/**
 * Proves the schema and the entities agree with each other.
 *
 * <p>Two things are written by hand in this service and can drift apart silently: the SQL in
 * {@code db/migration}, and the annotations on the entity classes. Nothing forces them to match. A
 * column renamed in one and not the other compiles perfectly and fails at the first query.
 *
 * <p>This is the test that makes that impossible to miss, and it is also the first time anything in
 * this module has run against a real database.
 */
class SchemaIT extends PostgresIT {

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	@Test
	void bothMigrationsAreRecordedAsApplied() {
		// Flyway keeps its own bookkeeping table. Asking IT rather than asking whether the tables
		// exist is the stronger question: it proves the migrations RAN, in order, and were recorded,
		// which is what makes the next startup skip them instead of trying again.
		List<String> applied = jdbc.queryForList(
				"SELECT script FROM flyway_schema_history WHERE success = true ORDER BY installed_rank",
				String.class);

		assertThat(applied)
				.containsExactly("V1__create_orders.sql", "V2__create_outbox.sql");
	}

	@Test
	void hibernateValidatesEveryMappingAgainstTheSchema() {
		// WHY this test looks like it asserts almost nothing: the assertion already happened.
		//
		// application.yml sets spring.jpa.hibernate.ddl-auto=validate, so during startup Hibernate
		// compared every entity mapping against the real tables Flyway had just created and would
		// have refused to build the EntityManagerFactory on any mismatch -- a missing column, a wrong
		// type, a misspelled table. The Spring context would then have failed, and this test would
		// never have run at all.
		//
		// So reaching this line is the result. What is left is to state explicitly which mappings
		// were covered, because a validated schema proves nothing about an entity Hibernate never
		// knew existed.
		assertThat(entityManagerFactory.getMetamodel().getEntities())
				.extracting(entityType -> entityType.getJavaType().getName())
				.contains(Order.class.getName(), OutboxRecord.class.getName());
	}
}
