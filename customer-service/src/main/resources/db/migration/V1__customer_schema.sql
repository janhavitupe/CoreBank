CREATE TABLE customers (
    id          UUID PRIMARY KEY,
    name        VARCHAR(120) NOT NULL,
    email       VARCHAR(160) NOT NULL UNIQUE,
    phone       VARCHAR(20),
    kyc_status  VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE kyc_records (
    id           UUID PRIMARY KEY,
    customer_id  UUID         NOT NULL REFERENCES customers (id),
    status       VARCHAR(20)  NOT NULL,
    note         VARCHAR(255),
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Added beyond the proposal's data model: the proposal names an auth module but no credentials storage.
CREATE TABLE credentials (
    customer_id    UUID PRIMARY KEY REFERENCES customers (id),
    password_hash  VARCHAR(100) NOT NULL,
    role           VARCHAR(20)  NOT NULL
);

CREATE TABLE refresh_tokens (
    token_hash   VARCHAR(64) PRIMARY KEY,
    customer_id  UUID        NOT NULL REFERENCES customers (id),
    expires_at   TIMESTAMPTZ NOT NULL
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
