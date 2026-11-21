-- 모든 서비스가 같이 쓰는 표. 서비스별 마이그레이션은 V2부터 시작한다.

-- Transactional Outbox: 상태 변경과 같은 트랜잭션에서 기록하고, 릴레이가 Kafka로 발행한다(ADR-0002).
CREATE TABLE outbox_events (
    id           BIGSERIAL PRIMARY KEY,
    event_id     UUID        NOT NULL UNIQUE,
    topic        TEXT        NOT NULL,
    message_key  TEXT        NOT NULL,
    payload      TEXT        NOT NULL,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);
CREATE INDEX outbox_events_unpublished ON outbox_events (id) WHERE published_at IS NULL;

-- Inbox: 같은 이벤트를 두 번 처리하지 않는다.
CREATE TABLE processed_events (
    event_id     UUID        NOT NULL,
    consumer     TEXT        NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, consumer)
);

-- 멱등키: 같은 키 + 같은 본문은 처음 응답을 돌려주고, 다른 본문은 409.
CREATE TABLE idempotency_keys (
    scope           TEXT        NOT NULL,
    key             TEXT        NOT NULL,
    request_hash    TEXT        NOT NULL,
    response_status INT,
    response_body   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (scope, key)
);
