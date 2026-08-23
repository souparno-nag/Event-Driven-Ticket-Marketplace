# T046 — The drift test: making a shell script and some Java constants agree

**What this task did:** added `TopicNameDriftTest`, which **reads `create-topics.sh` and compares it
against `Topics.java`**. Three tests, all passing, and each one proven to fail when the two disagree.

---

## The problem it closes

T044 and T045 left a duplication that could not be removed. Channel names exist in two places:

- **`Topics.java`** — what the services will publish and subscribe to
- **`infra/kafka-init/create-topics.sh`** — what actually creates the channels when the environment
  starts

The script cannot simply read the Java. Provisioning has to work at build step 1, when no jar has
been built and no Spring application exists — that is the whole reason the provisioner is a shell
script rather than the `NewTopic` beans R4 originally specified.

So the duplication stays. What can be removed is the *silence* of the failure.

### Why silence is the real problem

Rename `ORDER_CREATED` from `order.created` to `order.placed` and consider what happens:

- The Java compiles. It is a string constant; nothing about it is checked.
- The script keeps creating `order.created`, and reports success, because it is doing exactly what
  it says.
- The environment starts clean. `make up` passes. `make health` reports every component healthy.
- The service publishes to `order.placed`, a channel nobody created and nobody consumes.

**Nothing anywhere reports an error.** The saga just stops partway with no failure to look at, and
the investigation starts from "the order is stuck" — several layers away from a renamed string.

A bug that produces no error message is far more expensive than one that crashes, and this is one of
the purest examples: every individual component is behaving correctly and the system still does not
work.

---

## What the test actually does

The obvious test would be:

```java
assertThat(Topics.ALL).hasSize(7);
assertThat(Topics.dlt("x")).isEqualTo("x.DLT");
```

**That test is worthless for this purpose**, and it is worth being precise about why: both
assertions hold no matter what the script contains. They would pass on exactly the day the two files
disagree — which is the only day the test needed to do anything. It looks like coverage while
checking nothing that can drift.

So the test reads the file:

```java
private static final Pattern TOPICS_ARRAY = Pattern.compile("TOPICS=\\((.*?)\\)", Pattern.DOTALL);
```

It extracts the script's `TOPICS=( ... )` array, and asserts:

1. **The script's channels equal `Topics.ALL`** — `containsExactlyElementsOf`, so order counts too.
   Order matters here not because Kafka cares, but because the two lists are meant to be readable
   side by side; a reordering means one was edited without the other, catching the drift a step
   before it becomes a rename.
2. **The script's dead-letter suffix equals `Topics.DLT_SUFFIX`**, extracted from its
   `create "${topic}.DLT"` line — and separately that `Topics.dlt()` still *uses* that constant,
   since agreeing on a constant is not the same as the method callers invoke producing it.
3. **`Topics.ALL` has exactly seven entries, with no duplicates.** Partly because seven is the
   number the rest of the system is specified in terms of, and partly as the **anti-vacuity guard**
   for test 1: comparing two empty lists succeeds, so something has to assert the lists are not
   empty.

`Topics.java` was written expecting this. `DLT_SUFFIX` is package-private with the comment
*"Package-private so the drift test can read it"* — the test the earlier task anticipated is this
one.

---

## Finding the script from inside a test

```java
private static Path locateScript() {
	Path directory = Path.of("").toAbsolutePath();
	while (directory != null) {
		Path candidate = directory.resolve(SCRIPT_FROM_REPO_ROOT);
		if (Files.isRegularFile(candidate)) return candidate;
		directory = directory.getParent();
	}
	throw new IllegalStateException(...);
}
```

The tempting version is `Path.of("../infra/kafka-init/create-topics.sh")`, which works because Maven
runs tests with the module directory as the working directory. It stops working the moment anyone
runs the test from the repository root, or from an IDE with a different default — and the failure
would be a missing file, which reads like the script was deleted rather than like the test was run
from somewhere else.

Walking up from the working directory finds it either way, and the failure message names the
absolute path it searched from, so a genuine absence is diagnosable.

---

## Proving the test can fail

A test that has only ever passed is an untested test. This one was checked against **five separate
mutations**, from both directions, reverting after each:

| # | Mutation | Caught by | Message |
|---|---|---|---|
| 1 | Script: `order.created` → `order.placed` | test 1 | `and others were not expected` |
| 2 | Script: `.DLT` → `.dlq` | test 2 | `expected: ".DLT" but was: ".dlq"` |
| 3 | Script: delete `payment.failed` | test 1 | element missing |
| 4 | **Java**: `ORDER_CREATED = "order.new"` | test 1 | `but some elements were not found` |
| 5 | **Java**: `DLT_SUFFIX = ".DEAD"` | test 2 | `expected: ".DEAD" but was: ".DLT"` |

All five failed the build, each caught by the test that should have caught it, with a message naming
the actual disagreement. Mutations 4 and 5 matter most — the Java side is where a rename is most
likely to originate, since it is the file people edit while building features.

---

## What it does not cover

Worth stating, so the test is not trusted for more than it does.

It checks that both files **name the same channels**. It does not check the *settings* those channels
get — `--partitions 3` and `--replication-factor 1` are asserted by Scenario 5 in **T047**, against a
running broker, which is the only place that claim can honestly be made.

It also cannot detect a name that is wrong in *both* files identically. Nothing can; at that point
the two agree and there is no drift, just a decision.

---

## In one line

Two files have to say the same thing and no language feature can enforce it, so a test reads one and
compares it to the other — turning a silent runtime mystery into a build failure that names the
disagreement.
