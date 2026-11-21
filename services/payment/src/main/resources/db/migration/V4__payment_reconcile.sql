-- 재조회 횟수(ADR-0004). V3은 db/common이 쓴다(번호는 두 위치가 함께 쓴다).
ALTER TABLE payments ADD COLUMN reconcile_attempts INT NOT NULL DEFAULT 0;
