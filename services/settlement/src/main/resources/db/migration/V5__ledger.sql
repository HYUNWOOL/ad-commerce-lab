-- 복식부기 원장(ADR-0008). V1·V3은 db/common이 쓴다.

-- 이벤트 하나 = 분개 하나. source_event_id로 같은 이벤트를 두 번 기록하지 않는다(FR-403).
CREATE TABLE journal_entries (
    id              BIGSERIAL PRIMARY KEY,
    source_event_id UUID        NOT NULL UNIQUE,
    partner_id      TEXT        NOT NULL,
    kind            TEXT        NOT NULL, -- PAYMENT, CHARGE
    occurred_at     TIMESTAMPTZ NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE journal_lines (
    id         BIGSERIAL PRIMARY KEY,
    entry_id   BIGINT NOT NULL REFERENCES journal_entries (id),
    account    TEXT   NOT NULL, -- PG_RECEIVABLE, PARTNER_PREPAID, AD_REVENUE
    partner_id TEXT   NOT NULL,
    debit      BIGINT NOT NULL DEFAULT 0,
    credit     BIGINT NOT NULL DEFAULT 0,
    CHECK (debit >= 0 AND credit >= 0 AND (debit = 0) <> (credit = 0)) -- 한 줄은 한쪽만 양수
);
CREATE INDEX journal_lines_entry ON journal_lines (entry_id);
CREATE INDEX journal_lines_partner ON journal_lines (partner_id, account);

-- 커밋할 때 분개마다 차변 합 = 대변 합인지 본다. 코드가 한쪽 줄만 써도 커밋되지 않는다(FR-401).
CREATE FUNCTION journal_entry_balanced() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    diff BIGINT;
BEGIN
    SELECT COALESCE(SUM(debit), 0) - COALESCE(SUM(credit), 0) INTO diff FROM journal_lines WHERE entry_id = NEW.entry_id;
    IF diff <> 0 THEN
        RAISE EXCEPTION '분개 %의 차변 합과 대변 합이 % 다릅니다', NEW.entry_id, diff;
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER journal_lines_balanced
    AFTER INSERT ON journal_lines DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION journal_entry_balanced();

-- 원장은 고치지 않는다. 틀린 기록은 반대 분개를 새로 넣어 바로잡는다.
CREATE FUNCTION journal_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '원장(%)은 고치거나 지울 수 없습니다. 반대 분개로 바로잡습니다', TG_TABLE_NAME;
END $$;

CREATE TRIGGER journal_entries_append_only BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION journal_append_only();
CREATE TRIGGER journal_lines_append_only BEFORE UPDATE OR DELETE ON journal_lines
    FOR EACH ROW EXECUTE FUNCTION journal_append_only();
