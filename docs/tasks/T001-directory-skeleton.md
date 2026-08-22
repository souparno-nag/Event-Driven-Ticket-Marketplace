# T001 — Creating the directory skeleton

**What this task did:** created the empty folders the project will live in.

That sounds trivial, but the layout is not arbitrary. Java and Maven both expect very specific
folder names, and getting them wrong causes confusing "class not found" errors later. This
document explains what each folder is for and why it is named that way.

---

## What got created

```text
common-events/
└── src/
    ├── main/java/com/marketplace/events/     ← real code lives here
    └── test/java/com/marketplace/events/     ← tests live here

infra/
└── prometheus/                                ← monitoring config

docs/
└── tasks/                                     ← these explanation files
```

---

## Why `src/main/java` and `src/test/java`?

This is a Maven convention. Maven is the build tool this project uses — it compiles the code,
runs the tests, and packages everything up.

Maven follows a principle called **"convention over configuration"**. Rather than making you
write a config file saying "my source code is in this folder, my tests are in that folder",
Maven simply assumes:

| Folder | What Maven does with it |
|---|---|
| `src/main/java` | Compiles it and ships it in the final product |
| `src/test/java` | Compiles it and runs it as tests, but does **not** ship it |

That last distinction matters. Your tests should never end up inside the application you deploy —
they exist to check your work, not to run in production. Maven enforces that separation purely
based on which folder a file sits in.

If you put a file in the wrong one, nothing errors immediately. It just quietly does the wrong
thing, which is much harder to debug. So the layout is worth getting right at the start.

---

## Why `com/marketplace/events`?

That is the **package** — Java's way of grouping related classes and giving them unique names.

A package name is written with dots in code (`com.marketplace.events`) but must exist as nested
folders on disk (`com/marketplace/events`). Java requires these to match exactly. A class declared
as `package com.marketplace.events;` **must** sit in a folder path ending `com/marketplace/events`,
or compilation fails.

The convention of starting with a reversed domain name (`com.marketplace`) exists to prevent
collisions. If you use a library that also has a class called `OrderCreated`, the two are
distinguished by their full names — `com.marketplace.events.OrderCreated` versus
`com.someoneelse.OrderCreated` — so both can coexist in the same program.

---

## What is `common-events` for?

This project is being built as several separate services — one handling orders, one handling seat
inventory, one handling payments, and so on. Those services talk to each other by sending
**messages**.

For that to work, every service must agree on exactly what a message looks like. If the order
service sends a field called `orderId` but the payment service expects `order_id`, the
communication breaks.

`common-events` is the shared module holding those message definitions. Every other service will
depend on it, so there is exactly one definition of each message and no possibility of them
disagreeing. It is deliberately the very first thing built, because everything else depends on it.

---

## What are those `.gitkeep` files?

Git — the version control system tracking changes to this project — tracks **files**, not folders.
An empty folder is invisible to Git and simply will not be saved.

Since we want the folder structure committed now, before any real code exists, the usual trick is
to place a tiny empty file inside each empty folder. `.gitkeep` is the conventional name. It has no
special meaning to Git and does nothing on its own — it exists purely so the folder contains
something, and therefore gets tracked.

They will be deleted as real files arrive in those folders.

---

## What is `infra/` for?

Short for infrastructure. This project needs several supporting programs running alongside it — a
message broker, a couple of databases, a monitoring tool. Rather than installing each one manually,
they will be described in a configuration file and started with a single command.

`infra/` holds that configuration, kept separate from the application code so the two do not get
tangled together.

---

## How to check this worked

```bash
find common-events infra docs -type d
```

You should see the folder tree shown above. Nothing is compiled or runnable yet — that starts in
the next task, which creates the Maven build file telling Maven this project exists and how to
build it.
