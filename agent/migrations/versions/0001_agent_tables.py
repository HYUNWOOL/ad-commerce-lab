"""에이전트 위임·구매 예약·승인·도구 호출 멱등·감사 로그(ADR-0010).

Revision ID: 0001
Revises:
"""

from alembic import op

revision = "0001"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute(
        """
        -- 파트너가 에이전트에게 준 위임(FR-602). 금액은 원 단위 정수.
        CREATE TABLE mandates (
            id                  UUID PRIMARY KEY,
            partner_id          TEXT        NOT NULL,
            per_order_limit     BIGINT      NOT NULL CHECK (per_order_limit > 0),
            period_limit        BIGINT      NOT NULL CHECK (period_limit >= per_order_limit),
            period_days         INT         NOT NULL CHECK (period_days BETWEEN 1 AND 31),
            allowed_product_ids BIGINT[]    NOT NULL,
            expires_at          TIMESTAMPTZ NOT NULL,
            revoked             BOOLEAN     NOT NULL DEFAULT false,
            created_at          TIMESTAMPTZ NOT NULL DEFAULT now()
        );
        CREATE INDEX mandates_partner ON mandates (partner_id);

        -- 위임으로 산 것. 주문 API를 부르기 전에 RESERVED로 금액을 잡아 두어, 동시에 불러도 기간 한도를 넘지 않는다.
        CREATE TABLE purchases (
            id              UUID PRIMARY KEY,
            partner_id      TEXT        NOT NULL,
            mandate_id      UUID        NOT NULL REFERENCES mandates (id),
            idempotency_key TEXT        NOT NULL,
            product_id      BIGINT      NOT NULL,
            amount          BIGINT      NOT NULL CHECK (amount > 0),
            start_date      DATE        NOT NULL,
            end_date        DATE        NOT NULL,
            status          TEXT        NOT NULL, -- RESERVED, PLACED, FAILED
            order_id        UUID,
            failure_reason  TEXT,
            created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
            UNIQUE (mandate_id, idempotency_key)
        );
        CREATE INDEX purchases_mandate ON purchases (mandate_id, created_at);

        -- 한도 초과 구매·기준 이상 환불의 승인 요청(FR-603). 승인·거절은 사람만 한다(도구가 아님).
        CREATE TABLE approvals (
            id              UUID PRIMARY KEY,
            kind            TEXT        NOT NULL, -- PURCHASE, REFUND
            partner_id      TEXT,                 -- PURCHASE만 채운다. 그 파트너만 승인할 수 있다
            requested_by    TEXT        NOT NULL, -- partner:P-1 / operator:ops-1 (에이전트 연결의 주인)
            idempotency_key TEXT        NOT NULL,
            payload         JSONB       NOT NULL,
            reason          TEXT        NOT NULL,
            status          TEXT        NOT NULL, -- PENDING, APPROVED(실행 중), EXECUTED, REJECTED, FAILED
            decided_by      TEXT,
            result          JSONB,
            created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
            decided_at      TIMESTAMPTZ,
            UNIQUE (requested_by, idempotency_key)
        );
        CREATE INDEX approvals_pending ON approvals (kind, status, created_at);

        -- 쓰기 도구 호출 멱등(FR-604). 같은 키 + 같은 인자면 처음 응답, 다른 인자면 오류.
        CREATE TABLE tool_calls (
            caller          TEXT        NOT NULL,
            idempotency_key TEXT        NOT NULL,
            tool            TEXT        NOT NULL,
            request_hash    TEXT        NOT NULL,
            response        JSONB,
            created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
            PRIMARY KEY (caller, idempotency_key)
        );

        -- 모든 도구 호출의 감사 로그(FR-605). 고치거나 지울 수 없다.
        CREATE TABLE audit_log (
            id              BIGSERIAL PRIMARY KEY,
            at              TIMESTAMPTZ NOT NULL DEFAULT now(),
            caller          TEXT        NOT NULL,
            mandate_id      UUID,
            tool            TEXT        NOT NULL,
            input           JSONB       NOT NULL,
            decision        TEXT        NOT NULL, -- ALLOWED, APPROVAL_REQUIRED, DENIED, ERROR, READ
            reason          TEXT,
            result          JSONB,
            idempotency_key TEXT
        );
        CREATE FUNCTION audit_append_only() RETURNS trigger LANGUAGE plpgsql AS $$
        BEGIN
            RAISE EXCEPTION '감사 로그는 고치거나 지울 수 없습니다';
        END $$;
        CREATE TRIGGER audit_log_append_only BEFORE UPDATE OR DELETE ON audit_log
            FOR EACH ROW EXECUTE FUNCTION audit_append_only();
        """
    )


def downgrade() -> None:
    op.execute(
        "DROP TABLE audit_log; DROP FUNCTION audit_append_only(); DROP TABLE tool_calls; "
        "DROP TABLE approvals; DROP TABLE purchases; DROP TABLE mandates;"
    )
