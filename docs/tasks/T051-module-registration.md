# T051 — Adding a module should cost two edits (SC-008)

**What this task did:** added a throwaway module to the build, confirmed it took **exactly two file
changes**, used it to prove a claim T050 could not, then removed it.

```
 M pom.xml            <- 1 file changed, 1 insertion(+)
?? scratch-module/    <- one new pom.xml
```

That is the entire cost of registering a module.

---

## Why this is worth measuring

Seven service modules are still to come — `order-service`, `inventory-service`,
`payment-service`, `projection-service`, `api-gateway`, `auth-service`, `eureka-server`. If adding
one required touching four or five shared files, that cost gets paid seven times, and every one of
those edits is a chance to forget something or to configure it slightly differently from its
neighbours.

That is how build configuration drifts: not through a bad decision, but through seven
almost-identical copies made a week apart.

SC-008 turns "the build is well structured" into something falsifiable: **add a module and count the
edits.** Two is the answer, and anything more is a design problem visible immediately rather than
after the fourth service.

---

## Part A — the registration cost

The throwaway module's entire `pom.xml`:

```xml
<project ...>
	<modelVersion>4.0.0</modelVersion>
	<parent>
		<groupId>com.marketplace</groupId>
		<artifactId>ticket-marketplace</artifactId>
		<version>0.0.1-SNAPSHOT</version>
	</parent>
	<artifactId>scratch-module</artifactId>
</project>
```

Six meaningful lines: name yourself, name your parent. Nothing else.

And in the root, one line:

```diff
+		<module>scratch-module</module>
```

`./mvnw clean verify` then built three modules with the full test suite still passing:

```
[INFO] ticket-marketplace ......... SUCCESS [ 0.239 s]
[INFO] common-events .............. SUCCESS [ 7.320 s]
[INFO] scratch-module ............. SUCCESS [ 0.014 s]
[INFO] BUILD SUCCESS
```

**What the new module did not have to declare** is the actual result here:

- no Java version or compiler settings — inherited from the parent
- no dependency versions — the parent's `dependencyManagement` supplies roughly 400 pins
- no Surefire or Failsafe configuration — moved to the build root in T049, so a new module gets unit
  *and* integration test wiring by existing
- no `docker.api.version`, no repository declarations, no plugin versions

Each of those is something that would otherwise have been copied seven times. The T049 decision to
declare test plugins at the root rather than per module is what makes the Failsafe line true — had
it stayed in `common-events`, every service would have needed its own copy, and this task would have
found three edits instead of two.

---

## Part B — closing the gap T050 left open

T050 verified `./mvnw clean verify` on a clean checkout but had to record an honest limitation:
SC-004 says "builds and tests all modules **in dependency order**", and with a single module there
is no ordering to observe.

The throwaway module made that testable for the cost of a few lines, so rather than waiting for
build step 2, the experiment was extended. `scratch-module` was given a dependency on
`common-events`, and then deliberately declared **first** in the module list — the wrong order:

```xml
<modules>
	<module>scratch-module</module>   <!-- declared first, but depends on the other -->
	<module>common-events</module>
</modules>
```

If Maven built modules in the order they are listed, `scratch-module` would compile against a
`common-events` jar that did not exist yet, and the build would fail. It did not:

```
[INFO] ticket-marketplace ......... SUCCESS
[INFO] common-events .............. SUCCESS      <- built first
[INFO] scratch-module ............. SUCCESS      <- despite being listed first
[INFO] BUILD SUCCESS
```

**The reactor sorts by dependency, not by declaration.** Maven reads every module's `pom.xml`,
builds the dependency graph, and topologically sorts it. The `<modules>` list says *what* to build;
the dependencies say *when*.

That matters practically: when `order-service` arrives it can be appended to the end of the list
without anyone reasoning about placement, and it will still be built after the module it depends on.
The half of SC-004 that T050 had to leave open is now demonstrated.

---

## Removal

```bash
rm -rf scratch-module
git checkout pom.xml
```

`git status` returned to the committed state, and `./mvnw clean verify` still passes with two
modules and 39 tests. The throwaway is not committed — its purpose was the measurement, and the
measurement is what this document records.

---

## What it demonstrates

- **SC-008**: adding an empty module to the build requires changing only the build root's module
  registration and the new module's own descriptor. ✅ One line, plus one file.
- **SC-004 (the remaining half)**: modules are built in dependency order, proven by declaring them
  in the wrong order and watching the reactor correct it. ✅
- **FR-022**: a later service module is added by registration alone, without restructuring existing
  modules. ✅ No existing file other than the module list was touched.

---

## In one line

Adding a module costs one line in the root and one file in the module — and the reactor works out
the build order from the dependencies, so the seven services still to come can be appended without
anyone thinking about it.
