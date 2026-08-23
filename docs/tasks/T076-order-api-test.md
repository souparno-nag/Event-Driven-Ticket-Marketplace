# T076 — Specifying the HTTP contract, in a test that compiles today

**What this task did:** wrote the test for `POST /api/orders`'s HTTP behaviour — status codes,
headers, and which field a bad request names — and it compiles right now, even though the endpoint
it tests does not exist yet.

---

## Why this one compiles when T073–T075 do not

T073, T074, and T075 each import a Java class that has not been written yet, so the compiler stops
them cold. This file avoids that on purpose: it sends **raw JSON strings** over HTTP, rather than
constructing a `CreateOrderRequest` object.

```java
mockMvc.perform(post("/api/orders")
        .contentType(MediaType.APPLICATION_JSON)
        .content(body(userId, showId, "[\"A1\"]", "\"10.00\"")))
```

A test of the *wire contract* — what a client sends, what comes back — has no need to know that
`CreateOrderRequest` exists at all, and importing it here would tie this file to an implementation
detail it does not need. So this file has nothing left to fail to compile against, and I confirmed
it: it built cleanly and ran.

## Running it, and what "red" looks like when compilation succeeds

```text
Tests run: 7, Failures: 7, Errors: 0
AssertionError: Status expected:<202> but was:<404>
```

Every single test failed — and every one failed the *same* honest way: the server is up, MockMvc
sent the request, and Spring answered "no handler found for `/api/orders`" because
`OrderController` (T083) does not exist yet. That is a genuine, well-targeted assertion failure, not
a crash — the strongest available proof, in a compiled language, that this test asks the right
question of a system that cannot yet answer it. It will turn green once T083 and T084 land.

## What each test checks

| Test | Sends | Expects |
|---|---|---|
| `acceptedRequestReturns202WithLocationAndPendingStatus` | a valid request | `202`, a `Location` header, `status: PENDING` |
| `emptySeatListIsRejectedAndNothingIsRecorded` | `seatIds: []` | `400`, `field: seatIds`, order count unchanged |
| `duplicateSeatsAreRejectedAndNothingIsRecorded` | `seatIds: ["A1","A1"]` | `400`, `field: seatIds`, count unchanged |
| `missingBuyerIsRejectedAndNothingIsRecorded` | no `userId` | `400`, `field: userId`, count unchanged |
| `missingShowIsRejectedAndNothingIsRecorded` | no `showId` | `400`, `field: showId`, count unchanged |
| `negativeAmountIsRejectedAndNothingIsRecorded` | `amount: "-1.00"` | `400`, `field: amount`, count unchanged |
| `wrongScaleAmountIsRejectedAndNothingIsRecorded` | `amount: "10.5"` | `400`, `field: amount`, count unchanged |

The "nothing is recorded" half of each rejection test matters as much as the status code. A service
that rejects a bad request with the right status but still writes a row would pass a test that only
checked the response — these tests count `orderRepository.count()` before and after and require it
unchanged.

## MockMvc, and why it needs no running server

`@AutoConfigureMockMvc` wires up Spring's simulated servlet layer — the full `DispatcherServlet`,
the real `OrderController` once it exists, the real `ApiExceptionHandler`, all invoked in-process
without opening an actual network socket. That makes this file fast and self-contained: no port to
manage, no risk of colliding with anything else running on the machine. `OrderCapacityIT` (T077)
needs a genuine socket instead — that difference, and why, is explained in its own document.
