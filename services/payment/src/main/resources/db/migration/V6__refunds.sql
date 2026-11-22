-- 부분 환불(ADR-0011). 주문 하나에 환불 하나. 환불 ID가 PG 취소의 멱등키다.
CREATE TABLE refunds (
    id         UUID PRIMARY KEY,
    order_id   UUID        NOT NULL UNIQUE,
    payment_id UUID        NOT NULL REFERENCES payments (id),
    partner_id TEXT        NOT NULL,
    amount     BIGINT      NOT NULL CHECK (amount >= 0), -- delivery가 과금을 닫은 순간의 남은 예산
    status     TEXT        NOT NULL,                     -- REQUESTED, COMPLETED
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
