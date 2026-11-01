CREATE TABLE transactions (
    id               UUID PRIMARY KEY,
    type             VARCHAR(20)    NOT NULL,
    from_account     VARCHAR(20),
    to_account       VARCHAR(20),
    from_customer_id UUID,
    to_customer_id   UUID,
    amount           NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    -- Added beyond the proposal's data model: categorisation reads a description the proposal never stored.
    description      VARCHAR(255),
    status           VARCHAR(20)    NOT NULL,
    failure_code     VARCHAR(50),
    failure_message  VARCHAR(255),
    category         VARCHAR(30),
    customer_id      UUID           NOT NULL,
    idempotency_key  VARCHAR(100)   NOT NULL,
    correlation_id   VARCHAR(64),
    created_at       TIMESTAMPTZ    NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_transactions_pending ON transactions (created_at) WHERE status = 'PENDING';
CREATE INDEX idx_transactions_from ON transactions (from_account, created_at);
CREATE INDEX idx_transactions_to ON transactions (to_account, created_at);
CREATE INDEX idx_transactions_customer ON transactions (customer_id, created_at);

-- Keys are scoped per customer, so two customers can use the same key without colliding.
CREATE TABLE idempotency_keys (
    customer_id      UUID         NOT NULL,
    idempotency_key  VARCHAR(100) NOT NULL,
    request_hash     VARCHAR(64)  NOT NULL,
    transaction_id   UUID         NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (customer_id, idempotency_key)
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
