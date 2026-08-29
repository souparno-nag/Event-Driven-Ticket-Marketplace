-- The delivery guard: the durable note that a given message has already been handled by a given
-- consumer. This is the sole mechanism by which at-least-once delivery is made safe in this
-- service (spec.md Key Entities; FR-028).
--
-- Two deliberate deviations from the original brief's
-- `processed_events(event_id UUID PRIMARY KEY, consumer_name, processed_at)`, both recorded in
-- research.md R7.

CREATE TABLE processed_messages (
    -- The contract's `messageId` (common-events, step 1) -- never the show, and never named
    -- "event". Step 1 removed the word "event" as a field name specifically because it was
    -- ambiguous between a message and a concert (step 1 FR-003); a column called event_id in the
    -- one table whose entire job is identifying MESSAGES would reintroduce exactly that
    -- ambiguity.
    message_id     UUID          NOT NULL,

    -- Which consumer processed it. THE KEY IS COMPOSITE with message_id -- the first deliberate
    -- deviation from the brief, which put consumer_name in as an ordinary column beside a
    -- single-column primary key. With message_id ALONE as the primary key, consumer_name is
    -- decoration: the first consumer in this database to handle a message locks every OTHER
    -- consumer out of it too, silently, as a skip rather than an error. This service has only
    -- one consumer today, so nothing breaks yet -- the bug lands the moment a second consumer
    -- reads a channel a first one already reads, and it then presents as "one handler
    -- mysteriously never runs" (FR-029).
    consumer_name  VARCHAR(64)   NOT NULL,

    processed_at   TIMESTAMPTZ   NOT NULL DEFAULT now(),

    PRIMARY KEY (message_id, consumer_name)
);

-- No index beyond the primary key. Every read against this table is the guard's own
-- insert-or-conflict check against (message_id, consumer_name), which the primary key already
-- serves; nothing else queries this table.
