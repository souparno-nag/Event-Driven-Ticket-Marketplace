# T004 — Moving the Maven Wrapper to the project root

**What this task did:** moved four files up from the downloaded folder to the repository root:
`mvnw`, `mvnw.cmd`, `.mvn/`, and `.gitattributes`.

---

## What is the Maven Wrapper?

Maven is the build tool for this project. Normally you would install it yourself and run `mvn`.
The problem with that is **everyone gets a different version.**

You have Maven 3.6.3 installed, from 2020. Someone else cloning this project might have 3.9.x, or
4.x, or none at all. Builds that pass for one person then fail for another, for reasons that have
nothing to do with the code.

The **wrapper** solves this. It is a small script committed alongside your code. When you run it:

1. It reads `.mvn/wrapper/maven-wrapper.properties` to see which Maven version this project wants
2. If that version is not already on the machine, it downloads it automatically
3. It runs the build using *that* version

So instead of `mvn clean verify`, you run `./mvnw clean verify` — with the leading `./`, because
it is a script sitting in this folder rather than a command installed system-wide.

The properties file pins:

```
distributionUrl=.../apache-maven-3.9.16-bin.zip
```

**Maven 3.9.16.** Everyone who builds this project uses exactly that, regardless of what they have
installed. Your 3.6.3 is simply bypassed — nothing was uninstalled or changed.

If you have used Node.js, this is the same idea as committing a `.nvmrc`; in Gradle projects it is
the identical `gradlew` concept.

---

## Why the version matters here

Spring Boot 3.x requires **at least** Maven 3.6.3. Your installed version is exactly that number —
the bare minimum, with no margin.

Sitting on a minimum is uncomfortable. Some plugin versions resolve dependencies in ways older
Maven handles poorly, and when it goes wrong the error is usually an obscure dependency-resolution
message rather than a helpful "please upgrade Maven". Being several versions clear of the floor
avoids a category of confusing failure.

---

## The two script files

| File | For |
|---|---|
| `mvnw` | macOS and Linux (a shell script) |
| `mvnw.cmd` | Windows (a batch script) |

Both are committed so the project builds on any operating system. You will only ever use `mvnw`.

One detail: `mvnw` needs the **executable permission bit** set, or the shell refuses to run it with
"Permission denied". That was set with `chmod +x mvnw`. Git records this bit, so it survives being
cloned.

---

## What is `.gitattributes`?

It tells Git how to handle certain files. The one Spring Initializr generates handles **line
endings**.

Windows ends lines with two invisible characters (carriage return + line feed); macOS and Linux use
one (line feed). Without guidance, Git can rewrite these when files move between systems — which
corrupts a shell script, because the interpreter chokes on the stray carriage return.

`.gitattributes` marks `mvnw` as needing Unix line endings and `mvnw.cmd` as needing Windows ones,
so both keep working no matter who clones the repository.

---

## Why these files live at the root

Maven looks for the wrapper next to the `pom.xml` it is building. Our aggregator `pom.xml` sits at
the repository root, so the wrapper belongs there too.

They arrived inside `ticket-marketplace/` only because Spring Initializr packaged a self-contained
project. That folder is now nearly empty — one file remains, handled in the next task, after which
it is removed entirely.

---

## Try it yourself

```bash
./mvnw -version
```

**Expect**: a short pause on the very first run while it downloads Maven 3.9.16 (roughly 9 MB),
then output beginning:

```
Apache Maven 3.9.16 ...
Maven home: /home/souparno/.m2/wrapper/dists/...
Java version: 21.0.11 ...
```

Two things worth noticing:

- The version is **3.9.16**, not your installed 3.6.3 — the wrapper won
- "Maven home" points inside `~/.m2/wrapper/dists/`, a private copy this project downloaded.
  Your system Maven is untouched.

Subsequent runs skip the download and start immediately.
