-- The Order aggregate: a buyer's request to purchase specific seats for a show.
--
-- This service is the sole owner of these tables. No other service reads or writes them; everything
-- they need travels as messages on Kafka. That is what makes the saga choreographed rather than a
-- distributed monolith sharing one database.

CREATE TABLE orders (
    -- Assigned by the APPLICATION, not by the database. The identifier therefore exists before the
    -- row does, which is what lets the service return an order id even on a path where the write
    -- later fails -- and, more importantly, lets the same value be used as the saga correlation id
    -- and the Kafka partition key while the transaction is still open.
    id          UUID           PRIMARY KEY,

    user_id     UUID           NOT NULL,

    -- show_id, never event_id. Build step 1 renamed this deliberately: "event" already means "a
    -- message" everywhere else in this system, and an eventId that sometimes meant a concert and
    -- sometimes meant a message identity is a bug waiting for a tired afternoon.
    show_id     UUID           NOT NULL,

    -- NUMERIC, never float or double. Binary floating point cannot represent 0.10 exactly, so
    -- amounts drift by fractions of a penny under arithmetic. The simulated payment rule in build
    -- step 4 branches on the last digit of the amount and the step-9 load test asserts exact
    -- totals; both need exactness rather than closeness.
    amount      NUMERIC(19,2)  NOT NULL,

    status      VARCHAR(16)    NOT NULL,

    -- Optimistic locking. Hibernate reads this value, and on update writes
    --   WHERE id = ? AND version = ?
    -- so a second writer working from a stale copy updates zero rows and is told it lost, rather
    -- than silently overwriting the winner. Nothing updates an order in this build step -- the only
    -- transition is into PENDING at creation -- but the COLUMN has to exist now, because adding it
    -- later means a migration plus backfilling a version onto every existing row.
    version     BIGINT         NOT NULL DEFAULT 0,

    created_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ    NOT NULL DEFAULT now(),

    -- All three states are declared now although only PENDING is reachable until build step 4.
    -- Declaring them costs nothing and saves a migration; leaving them out would mean the first
    -- CONFIRMED order fails this constraint at exactly the wrong moment.
    CONSTRAINT orders_status_known
        CHECK (status IN ('PENDING', 'CONFIRMED', 'CANCELLED')),

    CONSTRAINT orders_amount_non_negative
        CHECK (amount >= 0)
);

-- Seats live in their own table rather than as an array column or a JSON blob.
--
-- WHY: the composite primary key below makes a duplicate seat on one order impossible IN THE
-- DATABASE. Application validation rejects duplicates too, but validation is a rule that holds only
-- while every code path remembers to call it; a primary key is an invariant that holds even when
-- something writes to this table by a route nobody anticipated.
--
-- TRADEOFF: reading one order now costs a join. Rejected the alternatives -- a TEXT[] column needs
-- a custom Hibernate type and enforces nothing, and a JSON blob makes the seats invisible to SQL
-- when you are trying to work out who else holds seat A1. A join on a handful of rows is not a cost
-- worth optimising away.
CREATE TABLE order_seats (
    -- ON DELETE CASCADE: seats have no meaning without their order, so the database removes them
    -- rather than leaving rows pointing at nothing.
    order_id  UUID         NOT NULL REFERENCES orders (id) ON DELETE CASCADE,

    -- A label unique within a show ("A1", "R12S4"), not a global identifier. Seat numbering is only
    -- meaningful inside one show, so a global seat id would invent a namespace nothing needs.
    seat_id   VARCHAR(32)  NOT NULL,

    PRIMARY KEY (order_id, seat_id)
);
