-- 노출 과금(ADR-0007). V1·V3은 db/common이 쓴다.

-- 예산의 원본. CampaignActivated로 만든다.
CREATE TABLE campaign_budgets (
    campaign_id UUID PRIMARY KEY,
    partner_id  TEXT        NOT NULL,
    budget      BIGINT      NOT NULL CHECK (budget > 0),
    cpv_price   BIGINT      NOT NULL CHECK (cpv_price > 0),
    spent       BIGINT      NOT NULL DEFAULT 0,
    status      TEXT        NOT NULL, -- ACTIVE, EXHAUSTED
    start_date  DATE        NOT NULL,
    end_date    DATE        NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (spent >= 0 AND spent <= budget) -- 예산을 넘겨 과금하지 않는다(S8). 코드가 틀려도 DB가 막는다
);

-- 받은 노출. event_id PK가 최종 중복 확인이다(S9).
-- shortcut: 계속 자란다. 운영이라면 날짜 파티션을 두고 중복 확인 기간이 지난 파티션을 지운다.
CREATE TABLE impression_events (
    event_id    UUID PRIMARY KEY,
    campaign_id UUID        NOT NULL,
    placement   TEXT        NOT NULL,
    occurred_at TIMESTAMPTZ NOT NULL,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- 배치 하나에서 캠페인 하나에 과금한 기록. CampaignCharged 한 건과 짝이다.
CREATE TABLE charges (
    id          UUID PRIMARY KEY,
    campaign_id UUID        NOT NULL REFERENCES campaign_budgets (campaign_id),
    impressions BIGINT      NOT NULL CHECK (impressions > 0),
    amount      BIGINT      NOT NULL CHECK (amount > 0),
    spent_total BIGINT      NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX charges_campaign ON charges (campaign_id);
