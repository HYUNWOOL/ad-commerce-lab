-- 기록 시점의 트레이스 문맥(W3C traceparent). 릴레이가 이 문맥 안에서 발행해 트레이스가 Outbox에서 끊기지 않는다(ADR-0005).
ALTER TABLE outbox_events ADD COLUMN trace_parent TEXT;
