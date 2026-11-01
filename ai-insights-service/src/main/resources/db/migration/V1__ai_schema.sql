CREATE TABLE processed_events (
    event_id      UUID PRIMARY KEY,
    processed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE outbox_events (
    id            UUID PRIMARY KEY,
    seq           BIGSERIAL,
    topic         VARCHAR(100) NOT NULL,
    event_key     VARCHAR(100) NOT NULL,
    payload       TEXT         NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at  TIMESTAMPTZ
);
CREATE INDEX idx_outbox_unpublished ON outbox_events (seq) WHERE published_at IS NULL;
