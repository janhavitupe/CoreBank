CREATE TABLE notifications (
    id           UUID PRIMARY KEY,
    customer_id  UUID         NOT NULL,
    channel      VARCHAR(20)  NOT NULL,
    message      VARCHAR(500) NOT NULL,
    status       VARCHAR(20)  NOT NULL,
    event_id     UUID         NOT NULL,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX idx_notifications_customer ON notifications (customer_id, created_at);

CREATE TABLE processed_events (
    event_id      UUID PRIMARY KEY,
    processed_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Added beyond the proposal's data model: a local copy of contact details built from customer
-- events, so sending an alert needs no synchronous call to Customer Service.
CREATE TABLE customer_contacts (
    customer_id  UUID PRIMARY KEY,
    name         VARCHAR(120) NOT NULL,
    email        VARCHAR(160) NOT NULL
);
