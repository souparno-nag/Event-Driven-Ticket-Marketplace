# T005 — The `.gitignore` file

**What this task did:** replaced the empty root `.gitignore` with a real one, and deleted the
now-empty `ticket-marketplace/` folder.

---

## What `.gitignore` is for

Git tracks every file in your project by default. Plenty of files should *not* be tracked:

- **Build output** — regenerated from source every time you build. Committing it bloats the
  repository and causes merge conflicts in files nobody edits by hand.
- **Editor settings** — your IntelliJ layout is yours; it should not be forced on anyone else.
- **Secrets** — passwords and API keys must never enter version control. Once committed, they
  live in the history forever, even if a later commit deletes them.
- **Large local state** — database files, container volumes.

`.gitignore` lists patterns for files Git should pretend not to see. They stay on your disk; Git
simply stops offering to commit them.

---

## How the patterns work

| Pattern | Meaning |
|---|---|
| `target/` | The trailing slash means *directories only*. Ignores any folder named `target` at any depth. |
| `*.class` | `*` matches anything. Ignores every compiled Java class file. |
| `!infra/.env` | A leading `!` **un-ignores** something an earlier pattern caught. |
| `.idea/` | One specific folder — IntelliJ's project settings. |

Order matters: patterns are read top to bottom, and a later `!` rule can rescue a file an earlier
rule excluded.

---

## The interesting decisions

### Secrets are default-deny

```gitignore
.env
*.env
!infra/.env
```

The first two lines ignore **every** `.env` file. `.env` files conventionally hold credentials —
database passwords, API tokens — so the safe default is to exclude them all and allow exceptions
deliberately.

The third line makes one exception. `infra/.env` will contain exactly one setting:

```
COMPOSE_PROFILES=core
```

...which chooses how many containers to start. That is configuration, not a secret, and it needs
to be committed so anyone cloning the project gets the same environment.

This "deny everything, then allow specific things" shape is worth internalising. The opposite —
listing each secret file individually — fails the moment someone adds a file you did not
anticipate. Getting this backwards is one of the most common ways credentials end up on GitHub.

### The negation lines under build output

```gitignore
target/
!**/src/main/**/target/
```

This looks contradictory. The reason: `target/` is broad and ignores a folder with that name
*anywhere*. If a project ever has a legitimate source folder called `target` — a package about
targeting, say — the broad rule would silently drop real code.

The `!` lines carve out anything under `src/`, which is always real source. `**` means "any number
of folders deep".

### `infra/data/`

Later tasks start containers for PostgreSQL, Kafka, and Elasticsearch. Those write their data into
`infra/data/`. It is regenerable local state and can reach hundreds of megabytes. Never committed.

---

## `ticket-marketplace/` is gone

That folder held the Spring Initializr download. Everything useful has now been relocated:

| Was | Went to | In task |
|---|---|---|
| `pom.xml` | root `pom.xml`, reshaped as an aggregator | T003 |
| `src/` + application class | deleted — an aggregator does not run | T003 |
| `mvnw`, `mvnw.cmd`, `.mvn/`, `.gitattributes` | repository root | T004 |
| `.gitignore` | merged into the root one | T005 |

With the last file moved, the folder was empty and removed. `git status` is clean again.

---

## Try it yourself

```bash
git status
```

**Expect**: `nothing to commit, working tree clean` (after this task's commit).

To watch `.gitignore` working, create a file it should exclude and confirm Git ignores it:

```bash
mkdir -p infra/data && touch infra/data/fake.db
git status --short
```

**Expect**: no mention of `fake.db`. Git can see the file; it has been told not to care.

You can ask Git *which rule* caught a file, which is handy when something is unexpectedly missing:

```bash
git check-ignore -v infra/data/fake.db
```

**Expect**: it names `.gitignore`, the line number, and the pattern that matched.

Clean up with `rm -rf infra/data`.

One caveat worth knowing: `.gitignore` only affects files Git is **not already tracking**. If a
file was committed before you added a rule for it, the rule is ignored and the file keeps being
tracked. Removing it then needs `git rm --cached <file>`.
