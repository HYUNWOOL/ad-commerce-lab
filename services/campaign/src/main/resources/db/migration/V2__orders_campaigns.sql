CREATE TABLE ad_orders (
    id             UUID PRIMARY KEY,
    partner_id     TEXT        NOT NULL,
    product_id     BIGINT      NOT NULL,
    budget         BIGINT      NOT NULL CHECK (budget > 0),
    cpv_price      BIGINT      NOT NULL CHECK (cpv_price > 0), -- 주문 시점 단가 스냅샷
    start_date     DATE        NOT NULL,
    end_date       DATE        NOT NULL CHECK (end_date >= start_date),
    status         TEXT        NOT NULL,
    failure_reason TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX ad_orders_partner ON ad_orders (partner_id);

CREATE TABLE campaigns (
    id         UUID PRIMARY KEY,
    order_id   UUID        NOT NULL UNIQUE REFERENCES ad_orders (id), -- 주문 하나에 캠페인 하나
    partner_id TEXT        NOT NULL,
    product_id BIGINT      NOT NULL,
    budget     BIGINT      NOT NULL CHECK (budget > 0),
    spent      BIGINT      NOT NULL DEFAULT 0 CHECK (spent >= 0 AND spent <= budget),
    cpv_price  BIGINT      NOT NULL,
    start_date DATE        NOT NULL,
    end_date   DATE        NOT NULL,
    status     TEXT        NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX campaigns_partner ON campaigns (partner_id);
