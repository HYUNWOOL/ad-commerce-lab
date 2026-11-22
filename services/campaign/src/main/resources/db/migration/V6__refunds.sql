-- 부분 환불(ADR-0011). 환불된 금액을 화면용으로 남긴다. 금액은 delivery가 과금을 닫는 순간 정한다.
ALTER TABLE campaigns ADD COLUMN refunded_amount BIGINT NOT NULL DEFAULT 0 CHECK (refunded_amount >= 0);
