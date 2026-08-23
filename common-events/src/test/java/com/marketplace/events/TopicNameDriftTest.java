package com.marketplace.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The channel names in {@code Topics} and the ones the environment actually creates are the same
 * names (FR-020).
 *
 * <p>Channel names live in two places, and that is not an accident anybody can fix from here.
 * {@code Topics.java} is what the services publish and subscribe to; {@code create-topics.sh} is
 * what provisions the channels when the environment starts. The script cannot read the Java,
 * because provisioning has to work at build step 1 when no jar exists yet — the reasoning is
 * recorded as a {@code TRADEOFF:} on the {@code kafka-init} service.
 *
 * <p>WHY that duplication needs a test rather than care: the failure it produces is silent. Rename a
 * constant here and the script keeps creating the old channel perfectly successfully, the
 * environment starts clean, {@code make health} reports everything healthy — and the service
 * publishes into a channel that does not exist, or subscribes to one nothing writes to. Nothing
 * anywhere reports an error. The saga simply stops partway with no failure to look at.
 *
 * <p>So this test reads the shell script and compares it against the constants. It is deliberately
 * not a test that the list has seven entries and the suffix is {@code .DLT} — those hold no matter
 * what the script says, which means they would pass on the exact day the two disagree.
 */
class TopicNameDriftTest {

	/** Where the provisioning script lives, relative to the repository root. */
	private static final Path SCRIPT_FROM_REPO_ROOT = Path.of("infra", "kafka-init", "create-topics.sh");

	/** The {@code TOPICS=( ... )} array literal. DOTALL so the group spans the newlines inside it. */
	private static final Pattern TOPICS_ARRAY = Pattern.compile("TOPICS=\\((.*?)\\)", Pattern.DOTALL);

	/** The dead-letter line in the loop body: {@code create "${topic}.DLT"}. */
	private static final Pattern DLT_CALL = Pattern.compile("create\\s+\"\\$\\{topic}(\\.\\w+)\"");

	@Test
	@DisplayName("the provisioning script creates exactly the channels named in Topics.ALL")
	void script_and_constants_name_the_same_channels() {
		assertThat(topicsDeclaredInScript())
				// Order is compared too, not just membership. The two lists are meant to be readable
				// side by side, and a reordering is a signal that one of them was edited without the
				// other — the very drift this test exists to catch, caught one step earlier.
				.as("channels in %s vs Topics.ALL", SCRIPT_FROM_REPO_ROOT)
				.containsExactlyElementsOf(Topics.ALL);
	}

	@Test
	@DisplayName("the script's dead-letter suffix is the one Topics.dlt() produces")
	void script_and_constants_agree_on_the_dead_letter_suffix() {
		Matcher matcher = DLT_CALL.matcher(readScript());
		if (!matcher.find()) {
			// Not an assertion failure but a structural one: the script no longer pairs each channel
			// with a dead letter in the shape this test knows how to read. Saying so plainly beats a
			// confusing mismatch against an empty string.
			fail("no `create \"${topic}.DLT\"` line found in %s — has the script been restructured?"
					.formatted(SCRIPT_FROM_REPO_ROOT));
		}

		String suffixInScript = matcher.group(1);

		assertThat(suffixInScript)
				.as("dead-letter suffix used by the provisioning script")
				.isEqualTo(Topics.DLT_SUFFIX);

		// The suffix constant agreeing is necessary but not sufficient: dlt() could stop using it.
		// This checks the method callers actually invoke against the name the script actually creates.
		assertThat(Topics.dlt(Topics.ORDER_CREATED))
				.isEqualTo(Topics.ORDER_CREATED + suffixInScript);
	}

	@Test
	@DisplayName("Topics.ALL holds exactly the seven message types")
	void all_holds_seven_channels() {
		// The count the rest of the system is specified in terms of: seven message types, and with
		// their dead-letter partners the fourteen channels FR-020 requires and SC-009 counts.
		// It also guards the test above from passing vacuously — comparing two empty lists succeeds.
		assertThat(Topics.ALL).hasSize(7).doesNotHaveDuplicates();
		assertThat(Topics.ALL.size() * 2).isEqualTo(14);
	}

	// --- reading the script ----------------------------------------------------------------------

	/**
	 * The channel names listed in the script's {@code TOPICS} array, in the order written there.
	 */
	private static List<String> topicsDeclaredInScript() {
		Matcher matcher = TOPICS_ARRAY.matcher(readScript());
		if (!matcher.find()) {
			fail("no TOPICS=( ... ) array found in %s".formatted(SCRIPT_FROM_REPO_ROOT));
		}

		List<String> names = new ArrayList<>();
		for (String line : matcher.group(1).split("\n")) {
			String entry = line.strip();
			// Skip the blank line after `TOPICS=(` and any comment the array may grow later.
			if (!entry.isEmpty() && !entry.startsWith("#")) {
				names.add(entry);
			}
		}
		return names;
	}

	private static String readScript() {
		Path script = locateScript();
		try {
			return Files.readString(script);
		}
		catch (IOException e) {
			throw new IllegalStateException("cannot read " + script.toAbsolutePath(), e);
		}
	}

	/**
	 * Finds the script by walking up from the working directory.
	 *
	 * <p>WHY not a fixed {@code ../infra/...}: that is correct when Surefire runs with the module as
	 * the working directory, and wrong the moment the test is run from the repository root or from an
	 * IDE with a different default. Walking up finds it in every case, and the failure message names
	 * the absolute path searched from, so a genuine absence is diagnosable rather than mysterious.
	 */
	private static Path locateScript() {
		Path directory = Path.of("").toAbsolutePath();
		while (directory != null) {
			Path candidate = directory.resolve(SCRIPT_FROM_REPO_ROOT);
			if (Files.isRegularFile(candidate)) {
				return candidate;
			}
			directory = directory.getParent();
		}
		throw new IllegalStateException(
				"could not find %s in any ancestor of %s".formatted(
						SCRIPT_FROM_REPO_ROOT, Path.of("").toAbsolutePath()));
	}
}
