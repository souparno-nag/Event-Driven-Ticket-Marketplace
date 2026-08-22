# T037 — `infra/.env`, the one knob

**What this task did:** created `infra/.env`, whose entire functional content is one line:

```
COMPOSE_PROFILES=core
```

That line decides which containers `make up` starts.

---

## Why the environment has a knob at all

Six services are defined in `docker-compose.yml`, and each carries a `profiles` tag (T029–T036). A
tagged service starts **only** when one of its profiles is active:

| Value | Components | Approx. RAM | Needed from |
|---|---|---|---|
| `core` | Kafka, PostgreSQL, Redis | ~1.1 GiB | build steps 1–5 |
| `obs` | Zipkin, Prometheus | ~0.5 GiB | build step 8 |
| `full` | core + obs + Elasticsearch | ~2.6 GiB | build step 6 onward |

Right now the project is on step 1 and nothing uses Elasticsearch, Zipkin, or Prometheus. Running
them anyway would cost 1.5 GiB for containers doing nothing, on a machine where memory is being
budgeted carefully enough that R10 sets a limit per container.

Profiles let you run what the current step needs. **The alternative approaches are all worse:**

- **Comment out the unused services.** They drift. Someone edits a commented block, or forgets to
  restore one, and the file no longer describes anything real.
- **Separate compose files per set.** The service definitions get duplicated, and duplicated
  definitions diverge.
- **Just run everything.** Fine until it isn't, and "why is my machine swapping" is a bad way to
  learn your environment is oversized.

Values also **combine**, and the result is the union:

```
COMPOSE_PROFILES=core,obs     # five services, ~1.6 GiB
```

That combination is genuinely useful — tracing work in step 8 without paying for Elasticsearch.

---

## Why this `.env` is committed when every other one is ignored

`.gitignore` (T005) bans `.env` files by default:

```gitignore
.env
*.env
!infra/.env      # ← this file, explicitly rescued
```

The default-deny is right: `.env` files conventionally carry credentials, and the safe posture is to
exclude them all and allow exceptions deliberately.

This one earns its exception because **it holds no secret** — only a choice about which containers
run. And it needs to be committed for a reason that runs the opposite way to the usual instinct:

> A setting that lives only on your machine is a setting nobody else can reproduce.

**FR-015** requires the environment to be reproducible. If the profile selection lived in your shell
profile, a colleague cloning the repository would get a different environment and no way to know
what yours was. Committing it makes the choice reviewable, diffable, and identical for everyone —
which is the whole argument for infrastructure-as-code applied to a single line.

Note that the exception is *reasoned*, in the same spirit as the committed PostgreSQL credentials in
T030. The rule is not "never commit `.env`", it is "never commit secrets" — and knowing the
difference is what lets you apply the rule instead of following it.

---

## How Compose finds the file, and how you override it

Compose reads `.env` from the **project directory**, which defaults to the directory containing the
compose file. So `infra/.env` is picked up automatically for `infra/docker-compose.yml` — with no
`--env-file` flag and regardless of where you run the command from. Both of these behave identically:

```bash
docker compose -f infra/docker-compose.yml config --services   # from the repo root
cd infra && docker compose config --services                   # from inside infra/
```

**A shell variable beats the file**, which is exactly what you want for a one-off:

```bash
COMPOSE_PROFILES=full docker compose -f infra/docker-compose.yml up -d
```

That temporary override is how T042 validates the complete environment without editing a committed
file, and how you would spin up observability for an afternoon and go back to `core` afterwards.

The precedence in general — **shell environment > `.env` file > Compose defaults** — is standard
across tooling. The committed file is the *default*, not a lock.

---

## A git detail worth knowing

Checking whether the exception actually worked, the obvious command misleads:

```bash
$ git check-ignore -v infra/.env
.gitignore:34:!infra/.env	infra/.env
$ echo $?
0
```

Exit code 0 usually means "yes, ignored". Here it means **"a rule matched"**, and the rule that
matched is a *negation* — the leading `!`. The file is **not** ignored.

The unambiguous check is to ask what git would actually offer to commit:

```bash
git ls-files --others --exclude-standard | grep -x 'infra/.env'
```

Output means the file is visible and addable. That is the question you actually care about, asked
directly, rather than inferring it from an exit code with a special case. Worth remembering the
general habit: **when a tool's answer has surprising semantics, find the command that answers your
real question.**

---

## Try it yourself

The file is doing its job before you start anything. Before it existed, no profile was active:

```bash
docker compose -f infra/docker-compose.yml config --services
```

**Expect**: `kafka postgres redis` — three services, with no `--profile` flag anywhere. The `.env`
selected them.

Then watch the override precedence:

```bash
COMPOSE_PROFILES=full    docker compose -f infra/docker-compose.yml config --services
COMPOSE_PROFILES=core,obs docker compose -f infra/docker-compose.yml config --services
```

**Expect**: six services, then five (`kafka postgres prometheus redis zipkin`) — the union of the two
profiles, confirming they combine rather than one replacing the other.

Now start the active set. There is still no `make up` — that arrives in T039:

```bash
docker compose -f infra/docker-compose.yml up -d
docker compose -f infra/docker-compose.yml ps
```

**Expect**: three containers, and no Elasticsearch or Prometheus anywhere. That is ~1.1 GiB rather
than ~2.6 GiB, for exactly the components steps 1–5 need.

To feel the difference, check what is actually reserved:

```bash
docker stats --no-stream --format 'table {{.Name}}\t{{.MemUsage}}\t{{.MemPerc}}'
```

**Expect**: three rows, each well inside the limit set for it — Kafka nearest its ceiling, Redis
barely registering.

---

## What comes next

**T038** — `depends_on` with `condition: service_healthy`, so nothing starts before what it needs is
actually serving. That is where the health checks written in T029–T035 start doing work rather than
merely reporting.
