# T142 — Specifying `SeatKey`, before it exists

**What this task did:** wrote the unit test for `SeatKey` — the class that builds the Redis key a seat
hold lives under — against a class T151 has not yet created.

---

## Confirmed to fail, for the right reason

```text
SeatKeyTest.java:33: error: cannot find symbol
  symbol:   variable SeatKey
  location: class SeatKeyTest
```

Verified directly with `javac`, not merely assumed from reading the file: four errors, every one of
them `SeatKey`, and nothing else. That is the entire and only problem with this file — the intended
state, not a regression, and it is what Constitution Principle II's "fail before, pass after" rule
means when satisfied structurally rather than ceremonially.

## Why three assertions, and why these three specifically

The three tests aren't arbitrary coverage — each one guards against a distinct way a key builder can
go wrong while still *looking* correct:

**`isBuiltFromShowIdAndSeatLabel`** is the obvious one: the key has to actually look like
`seat:{showId}:{seatId}`, matching the contract literally.

**`isStableAcrossTwoDifferentMessagesForTheSameOrder`** exists because guarantee 3 of
`contracts/seat-lock-scripts.md` — a key already holding this order's id counts as acquirable — only
means anything if a redelivered message and the message it duplicates produce the *identical* key.
Two `OrderCreated` records differing only in `messageId` stand in for exactly that: a genuine
redelivery, or an entirely separate request for the same seats, look identical on this one axis, and
this test is what confirms `SeatKey` treats them as the same key either way.

**`messageIdNeverAppearsInTheKey`** is the one that catches the actual bug this whole exercise exists
to prevent, stated as directly as possible: `OrderCreated` exposes both `showId()` and `messageId()` as
`UUID` accessors of the identical type. A key builder that reads the wrong one *compiles perfectly* —
nothing in the type system catches it — and produces a key that is unique per delivery instead of per
show-and-seat, silently deleting the entire mutual-exclusion guarantee this service exists to provide.
Every other test in this project's own house style would still pass against that bug; only an
assertion that explicitly looks for `messageId`'s value inside the key catches it.

## What this specifies for T151

`SeatKey.of(UUID showId, String seatId)` — a static method returning the key as a `String`. That
signature is now the contract T151 has to satisfy, the same way `OutboxRelayIT` fixed
`OutboxRelay.pollAndPublish()`'s shape in order-service before that class existed.
