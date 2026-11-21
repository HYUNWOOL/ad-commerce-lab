CREATE TABLE payments (
    id                UUID PRIMARY KEY,
    order_id          UUID        NOT NULL UNIQUE, -- 주문 하나에 결제 하나(FR-203)
    partner_id        TEXT        NOT NULL,
    amount            BIGINT      NOT NULL CHECK (amount > 0),
    status            TEXT        NOT NULL,
    pg_transaction_id TEXT,
    failure_reason    TEXT,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX payments_status ON payments (status) WHERE status IN ('REQUESTED', 'UNKNOWN');
