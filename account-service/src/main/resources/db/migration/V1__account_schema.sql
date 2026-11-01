CREATE TABLE accounts (
    id                     UUID PRIMARY KEY,
    customer_id            UUID           NOT NULL,
    account_number         VARCHAR(20)    NOT NULL UNIQUE,
    type                   VARCHAR(20)    NOT NULL,
    balance                NUMERIC(19, 4) NOT NULL DEFAULT 0 CHECK (balance >= 0),
    status                 VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE',
    version                BIGINT         NOT NULL DEFAULT 0,
    low_balance_alerted_on DATE,
    created_at             TIMESTAMPTZ    NOT NULL DEFAULT now()
);
CREATE INDEX idx_accounts_customer ON accounts (customer_id);

-- Added beyond the proposal's data model. The unique constraint makes a posting idempotent at the
-- point where money moves, and the rows give an audit trail to reconcile balances against.
CREATE TABLE ledger_entries (
    id              UUID PRIMARY KEY,
    transaction_id  UUID           NOT NULL,
    account_id      UUID           NOT NULL REFERENCES accounts (id),
    entry_type      VARCHAR(10)    NOT NULL,
    amount          NUMERIC(19, 4) NOT NULL CHECK (amount > 0),
    balance_after   NUMERIC(19, 4) NOT NULL,
    created_at      TIMESTAMPTZ    NOT NULL DEFAULT now(),
    CONSTRAINT uq_ledger_transaction_entry UNIQUE (transaction_id, entry_type)
);
CREATE INDEX idx_ledger_account ON ledger_entries (account_id);

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
