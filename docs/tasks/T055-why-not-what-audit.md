# T055 — Auditing the comments, and two detectors that did not work

**What this task did:** checked the project constraint that *every non-obvious line gets a WHY
comment, not a WHAT comment*. **No WHAT comments were found.** Getting to that answer honestly took
three attempts, and the two failures are the more instructive part.

---

## The distinction being enforced

A **WHAT** comment restates the code in English:

```java
// Set the timeout to 3 seconds
timeout = 3;
```

It adds nothing — the code already said that — and it now has to be maintained. Change the timeout
to 5 and the comment is a lie that reads as authoritative.

A **WHY** comment explains what the code cannot:

```java
// 3s, because the gateway gives up at 5s and a fallback needs room to emit
// the compensating event before that.
timeout = 3;
```

That is unrecoverable from the source. Nobody deletes the `3` and reconstructs the reasoning.

The rule matters most in this project because it is built to be **explained in an interview**. Code
you can read cold is code whose decisions are written down.

---

## Attempt 1: word overlap — invalid

The first idea was to flag comments whose words heavily overlap the line beneath them, on the theory
that restating code reuses its identifiers.

It reported **0 flagged out of 582 comments**, which is exactly the answer I wanted, and that is why
it was worth distrusting. So I injected two textbook WHAT comments and re-ran it.

**It caught neither.** The result was not "the codebase is clean"; it was "this measurement does not
measure anything". Had I stopped at the first run, this document would be recording a clean audit
that was never performed.

## Attempt 2: solo comments — a broken control

The second detector looked for **single-line comments sitting alone directly above code**, which is
where a WHAT comment naturally lives. A comment inside a paragraph of prose is almost never a
restatement; a lone line above an assignment often is.

The control failed again — 0 flagged with an injected WHAT comment present and verified on line 134.
But this time the *control* was wrong, not the detector: the injected comment had landed at the
bottom of an existing comment block, so it was not solo. I moved it and it landed at the bottom of a
different block. The Makefile is commented densely enough that **every line of code already has a
block above it**, which turned out to be a finding rather than an obstacle.

## Attempt 3: the sweep that worked

Run across all sources, the same detector found **11** solo comments — which is what established it
works. A detector that returns 0 everywhere is indistinguishable from a broken one; a detector that
returns 0 on one file and 11 across the rest is discriminating.

---

## The 11, reviewed individually

| Location | Comment | Verdict |
|---|---|---|
| `Validation.java:135` | *Not instantiable: a namespace for static rules, with no state of its own.* | WHY — explains a private constructor |
| `Topics.java:98` | *Not instantiable: a namespace for constants, not a thing with behaviour.* | WHY — same |
| `EventJson.java:80` | *Not instantiable: a factory, not a thing with state.* | WHY — same |
| `NamingConventionTest.java:104` | *Nested and anonymous classes carry a `$` and are not part of the contract.* | WHY — justifies the filter |
| `TopicNameDriftTest.java:106` | *Skip the blank line after `TOPICS=(` and any comment the array may grow later.* | WHY — including the future case |
| `OrderingGuaranteeIT.java:251` | *`sagaId == orderId` is a contract rule.* | WHY — the same value appears twice |
| `OrderingGuaranteeIT.java:281` | *Every Nth order, so each order has exactly one owning thread.* | WHY — the correctness of the test |
| `ContractRoundTripTest.java:135` | *And that `WRITE_BIGDECIMAL_AS_PLAIN` is doing its job on the wire itself.* | WHY — why a second assertion |
| `docker-compose.yml:371` | *`/-/healthy` is Prometheus's readiness endpoint. wget, because this image is busybox-based.* | WHY — why not curl |
| `docker-compose.yml:419` | *Read-only: the job runs the script, it does not edit it.* | WHY — justifies `:ro` |
| `prometheus.yml:15` | *Only relevant once alerting rules exist; stated now so the file has one obvious place to grow.* | WHY — why a setting nothing uses |

Every one gives a reason. Several answer the sharpest version of the question — *why not the obvious
alternative* — which is the form that survives longest.

The three "not instantiable" comments look like near-duplicates, and they are: the same idiom
appearing in three utility classes. Each states why the class has a private constructor, which is
genuinely non-obvious to someone who has not met the pattern.

---

## The density picture

| | Lines |
|---|---|
| Code | 1,199 |
| Comment | 1,244 |
| **Ratio** | **1.04 comment lines per line of code** |

The distribution is more interesting than the total:

- **Enums are 6–9× commented.** `PaymentFailureReason` is 6 lines of code and 56 of explanation —
  because the code is three constant names and everything that matters is *why those three, split on
  "is it certain no money moved?"*. Constant names carry no reasoning at all.
- **Test files sit at 0.15–0.58.** `ValidationTest` is the lowest at 0.15, which is right: a test
  named `rejects_money_with_more_than_two_decimal_places` explains itself, and the reasoning lives in
  the rule it tests.
- **Infrastructure sits at 1.1–2.6.** `docker-compose.yml` has 268 comment lines against 168 of
  configuration, because almost every value there is a decision — a memory limit, a listener
  address, a health check — and none of them says why it is what it is.

Where reasoning is recoverable from the code, the ratio is low. Where it is not, it is high. That is
the shape you want, and it is not something you get by aiming at a number.

---

## What it demonstrates

- **Project constraint**: *every non-obvious line gets a WHY comment, not a WHAT comment.* ✅ No
  restating comment found across 996 comment lines in 26 files.
- **Constitution**: decisions are explained where they are made rather than in a separate document
  that drifts. ✅

---

## In one line

No WHAT comments — but the finding only means something because the first two ways I tried to
establish it were shown to be incapable of detecting one.
