package com.marketplace.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.lang.reflect.RecordComponent;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The word "event" is not a field name anywhere in this module (FR-003, SC-007).
 *
 * <p>The original brief used {@code eventId} for two unrelated things: the identity of a message and
 * the identity of the concert being ticketed. Both are {@code UUID}, so a call site could pass one
 * where the other was meant and compile cleanly. The contracts split them into {@code messageId} and
 * {@code showId}.
 *
 * <p>WHY that fix needs a test at all: a naming rule enforced only by review survives exactly as long
 * as everyone who edits the module remembers it. This asserts it mechanically, so the day someone
 * adds an eighth message type with an {@code eventId} component, the build says so.
 *
 * <p>The scan reads compiled classes from the build output directory rather than taking a
 * hand-written list of the seven types. A hand-written list would need updating by the same person
 * who just forgot the naming rule, which is precisely the reader this test exists to catch.
 */
class NamingConventionTest {

	private static final String PACKAGE_PATH = "com/marketplace/events";

	@Test
	@DisplayName("no record component is named eventId, or mentions \"event\" at all")
	void no_component_uses_the_word_event() {
		List<String> offenders = recordsInPackage().stream()
				.flatMap(record -> Arrays.stream(record.getRecordComponents())
						.map(component -> record.getSimpleName() + "." + component.getName()))
				// Deliberately broader than a check for exactly "eventId": data-model.md bans the word
				// outright, because eventName or eventTime would reintroduce the same ambiguity in a
				// form a narrower test would wave through.
				.filter(name -> name.toLowerCase().contains("event"))
				.toList();

		assertThat(offenders)
				.as("record components must not use the word \"event\" — use messageId or showId")
				.isEmpty();
	}

	@Test
	@DisplayName("every message carries messageId")
	void every_record_has_a_message_id() {
		for (Class<?> record : recordsInPackage()) {
			assertThat(componentNames(record))
					.as("%s must carry the envelope's messageId", record.getSimpleName())
					.contains("messageId");
		}
	}

	@Test
	@DisplayName("messageId and showId coexist as distinct components on OrderCreated")
	void message_id_and_show_id_are_distinct() {
		// OrderCreated is the one message carrying both, which makes it the place the original
		// collision would have happened. Both are UUID, so only the names keep them apart.
		List<String> names = componentNames(OrderCreated.class);

		assertThat(names).contains("messageId", "showId");
		assertThat(OrderCreated.class.getRecordComponents())
				.filteredOn(component -> component.getName().equals("messageId")
						|| component.getName().equals("showId"))
				.hasSize(2);
	}

	@Test
	@DisplayName("the package holds exactly the seven message types")
	void there_are_exactly_seven_message_records() {
		// Doubles as a guard on the scan itself: a scan that silently found nothing would make every
		// other test in this file vacuously true, which is the failure mode of any reflective test.
		assertThat(recordsInPackage()).hasSize(7);
	}

	private static List<String> componentNames(Class<?> record) {
		return Arrays.stream(record.getRecordComponents())
				.map(RecordComponent::getName)
				.toList();
	}

	/**
	 * Every record compiled into this package, found by listing the build output directory.
	 *
	 * <p>Uses a main-source class to locate the directory, so the scan reads {@code target/classes}
	 * rather than the test classes sitting beside this file.
	 */
	private static List<Class<?>> recordsInPackage() {
		try {
			Path classesDir = Path.of(Topics.class.getProtectionDomain().getCodeSource().getLocation().toURI());
			try (Stream<Path> files = Files.list(classesDir.resolve(PACKAGE_PATH))) {
				return files
						.map(path -> path.getFileName().toString())
						.filter(fileName -> fileName.endsWith(".class"))
						// Nested and anonymous classes carry a $ and are not part of the contract.
						.filter(fileName -> !fileName.contains("$"))
						// Explicit type witness: without it each load() produces its own capture of ?, and
						// the resulting List<Class<capture#1>> will not convert to List<Class<?>>.
						.<Class<?>>map(fileName -> load(fileName.substring(0, fileName.length() - ".class".length())))
						.filter(Class::isRecord)
						.toList();
			}
		} catch (IOException | URISyntaxException e) {
			throw new IllegalStateException("could not scan " + PACKAGE_PATH + " for records", e);
		}
	}

	private static Class<?> load(String simpleName) {
		try {
			return Class.forName("com.marketplace.events." + simpleName);
		} catch (ClassNotFoundException e) {
			throw new IllegalStateException("compiled class not loadable: " + simpleName, e);
		}
	}
}
