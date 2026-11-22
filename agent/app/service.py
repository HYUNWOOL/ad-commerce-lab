"""도구와 승인의 흐름(ADR-0010). 결정은 policy.py, 업무 호출은 backend.py가 하고, 여기서는 순서·잠금·기록을 맡는다."""

import hashlib
import json
import uuid
from collections.abc import Callable
from datetime import UTC, date, datetime, timedelta
from typing import Any, Literal

from psycopg import Connection
from psycopg.types.json import Jsonb
from psycopg_pool import ConnectionPool
from pydantic import BaseModel

from app.backend import Backend, BackendError, BackendUnavailable
from app.policy import Mandate, decide_purchase, decide_refund

REFUND_APPROVAL_THRESHOLD = 100_000


class ToolResult(BaseModel):
    """쓰기 도구의 결과. 같은 멱등키로 다시 부르면 이 값이 그대로(replayed=True) 돌아간다."""

    decision: Literal["ALLOWED", "APPROVAL_REQUIRED", "DENIED"]
    reason: str
    status: str  # PLACED, FAILED, PENDING_APPROVAL, NOT_EXECUTED, REFUND_REQUESTED
    order_id: str | None = None
    approval_id: str | None = None
    refund_quote: int | None = None
    replayed: bool = False


class Retryable(Exception):
    """업무 서비스가 잠시 응답하지 않는다. 같은 멱등키로 다시 부르면 이어서 처리한다."""


class Rejected(Exception):
    """요청 자체가 잘못됐다(멱등키 없음, 같은 키를 다른 인자로 재사용)."""


class NotFound(Exception):
    """없거나, 이 신원이 볼 수 없는 것. 둘을 구분해 알려 주지 않는다."""


def _uuid(value: str | None) -> uuid.UUID | None:
    try:
        return uuid.UUID(value) if value else None
    except ValueError:
        return None


def _mandate(row: dict) -> Mandate:
    return Mandate(
        id=str(row["id"]),
        partner_id=row["partner_id"],
        per_order_limit=row["per_order_limit"],
        period_limit=row["period_limit"],
        period_days=row["period_days"],
        allowed_product_ids=frozenset(row["allowed_product_ids"]),
        expires_at=row["expires_at"],
        revoked=row["revoked"],
    )


class AgentService:
    def __init__(self, pool: ConnectionPool, backend: Backend):
        self.pool = pool
        self.backend = backend

    # ---------- 감사·멱등 ----------

    def audit(self, caller: str, mandate_id: str | None, tool: str, input: dict, decision: str,
              reason: str | None = None, result: Any = None, key: str | None = None) -> None:
        with self.pool.connection() as conn:
            conn.execute(
                "INSERT INTO audit_log (caller, mandate_id, tool, input, decision, reason, result, idempotency_key)"
                " VALUES (%s, %s, %s, %s, %s, %s, %s, %s)",
                (caller, _uuid(mandate_id), tool, Jsonb(input), decision, reason,
                 None if result is None else Jsonb(result), key),
            )

    def _write_tool(self, caller: str, mandate_id: str | None, tool: str, key: str, args: dict,
                    run: Callable[[], ToolResult]) -> ToolResult:
        """쓰기 도구 공통: 같은 키 + 같은 인자면 처음 결과, 다른 인자면 오류. 일시 오류는 저장하지 않아 다시 부르면 이어서 한다."""
        if not key or len(key) > 200:
            self.audit(caller, mandate_id, tool, args, "ERROR", "idempotency_key 없음")
            raise Rejected("idempotency_key(1~200자)가 필요합니다")
        digest = hashlib.sha256(json.dumps([tool, args], sort_keys=True).encode()).hexdigest()
        with self.pool.connection() as conn:
            conn.execute(
                "INSERT INTO tool_calls (caller, idempotency_key, tool, request_hash) VALUES (%s, %s, %s, %s)"
                " ON CONFLICT DO NOTHING",
                (caller, key, tool, digest),
            )
            saved = conn.execute(
                "SELECT request_hash, response FROM tool_calls WHERE caller = %s AND idempotency_key = %s", (caller, key)
            ).fetchone()
        if saved["request_hash"] != digest:
            self.audit(caller, mandate_id, tool, args, "ERROR", "같은 멱등키를 다른 인자로 재사용", key=key)
            raise Rejected("이 idempotency_key는 다른 요청에 이미 쓰였습니다. 새 요청이면 새 키를 쓰세요")
        if saved["response"] is not None:
            result = ToolResult.model_validate(saved["response"]).model_copy(update={"replayed": True})
        else:
            try:
                result = run()
            except Retryable as e:
                self.audit(caller, mandate_id, tool, args, "ERROR", str(e), key=key)
                raise
            with self.pool.connection() as conn:
                conn.execute(
                    "UPDATE tool_calls SET response = %s WHERE caller = %s AND idempotency_key = %s AND response IS NULL",
                    (Jsonb(result.model_dump(mode="json")), caller, key),
                )
        self.audit(caller, mandate_id, tool, args, result.decision, result.reason, result.model_dump(mode="json"), key)
        return result

    def _create_approval(self, conn: Connection, kind: str, partner_id: str | None, requested_by: str, key: str,
                         payload: dict, reason: str) -> str:
        conn.execute(
            "INSERT INTO approvals (id, kind, partner_id, requested_by, idempotency_key, payload, reason, status)"
            " VALUES (%s, %s, %s, %s, %s, %s, %s, 'PENDING') ON CONFLICT DO NOTHING",
            (uuid.uuid4(), kind, partner_id, requested_by, key, Jsonb(payload), reason),
        )
        row = conn.execute(
            "SELECT id FROM approvals WHERE requested_by = %s AND idempotency_key = %s", (requested_by, key)
        ).fetchone()
        return str(row["id"])

    # ---------- 위임(사람이 REST로 만든다) ----------

    def create_mandate(self, partner_id: str, per_order_limit: int, period_limit: int, period_days: int,
                       allowed_product_ids: list[int], expires_at: datetime) -> dict:
        with self.pool.connection() as conn:
            return conn.execute(
                "INSERT INTO mandates (id, partner_id, per_order_limit, period_limit, period_days, allowed_product_ids,"
                " expires_at) VALUES (%s, %s, %s, %s, %s, %s, %s) RETURNING *",
                (uuid.uuid4(), partner_id, per_order_limit, period_limit, period_days, allowed_product_ids, expires_at),
            ).fetchone()

    def mandates(self, partner_id: str) -> list[dict]:
        with self.pool.connection() as conn:
            return conn.execute(
                "SELECT * FROM mandates WHERE partner_id = %s ORDER BY created_at DESC LIMIT 20", (partner_id,)
            ).fetchall()

    def revoke_mandate(self, partner_id: str, mandate_id: str) -> dict:
        with self.pool.connection() as conn:
            row = conn.execute(
                "UPDATE mandates SET revoked = true WHERE id = %s AND partner_id = %s RETURNING *",
                (_uuid(mandate_id), partner_id),
            ).fetchone()
        if row is None:
            raise NotFound(f"위임 {mandate_id} 이(가) 없습니다")
        return row

    @staticmethod
    def _used(conn: Connection, mandate: dict) -> int:
        """기간 한도에 잡힌 금액: 예약(RESERVED)과 주문 완료(PLACED). 실패한 구매는 풀린다."""
        return conn.execute(
            "SELECT COALESCE(SUM(amount), 0) AS used FROM purchases WHERE mandate_id = %s"
            " AND status IN ('RESERVED', 'PLACED') AND created_at > now() - make_interval(days => %s)",
            (mandate["id"], mandate["period_days"]),
        ).fetchone()["used"]

    def my_mandate(self, partner_id: str, mandate_id: str | None) -> dict:
        with self.pool.connection() as conn:
            row = conn.execute(
                "SELECT * FROM mandates WHERE id = %s AND partner_id = %s", (_uuid(mandate_id), partner_id)
            ).fetchone()
            if row is None:
                raise NotFound("이 연결에 묶인 위임이 없습니다")
            used = self._used(conn, row)
        return {**row, "used_in_period": used, "remaining_in_period": max(row["period_limit"] - used, 0)}

    # ---------- 파트너 도구 ----------

    def place_order(self, partner_id: str, mandate_id: str | None, product_id: int, budget: int, start_date: date,
                    days: int, key: str) -> ToolResult:
        caller = f"partner:{partner_id}"
        args = {"mandate_id": mandate_id, "product_id": product_id, "budget": budget,
                "start_date": str(start_date), "days": days}
        return self._write_tool(caller, mandate_id, "place_order", key, args,
                                lambda: self._place(partner_id, mandate_id, product_id, budget, start_date, days, key))

    def _place(self, partner_id: str, mandate_id: str | None, product_id: int, budget: int, start_date: date,
               days: int, key: str) -> ToolResult:
        with self.pool.connection() as conn:
            # 위임 행을 잠가, 같은 위임으로 동시에 들어온 구매가 한도를 차례로 계산하게 한다
            row = conn.execute("SELECT * FROM mandates WHERE id = %s FOR UPDATE", (_uuid(mandate_id),)).fetchone()
            purchase = row and conn.execute(
                "SELECT * FROM purchases WHERE mandate_id = %s AND partner_id = %s AND idempotency_key = %s",
                (row["id"], partner_id, key),
            ).fetchone()
            if not purchase:
                used = self._used(conn, row) if row else 0
                decision = decide_purchase(_mandate(row) if row else None, partner_id, product_id, budget, used,
                                           datetime.now(UTC))
                if decision.kind == "DENIED":
                    return ToolResult(decision="DENIED", reason=decision.reason, status="NOT_EXECUTED")
                end_date = start_date + timedelta(days=days - 1)
                if decision.kind == "APPROVAL_REQUIRED":
                    payload = {"mandate_id": mandate_id, "product_id": product_id, "budget": budget,
                               "start_date": str(start_date), "end_date": str(end_date)}
                    approval_id = self._create_approval(conn, "PURCHASE", partner_id, f"partner:{partner_id}", key,
                                                        payload, decision.reason)
                    return ToolResult(decision="APPROVAL_REQUIRED", reason=decision.reason, status="PENDING_APPROVAL",
                                      approval_id=approval_id)
                purchase = conn.execute(
                    "INSERT INTO purchases (id, partner_id, mandate_id, idempotency_key, product_id, amount,"
                    " start_date, end_date, status) VALUES (%s, %s, %s, %s, %s, %s, %s, %s, 'RESERVED') RETURNING *",
                    (uuid.uuid4(), partner_id, row["id"], key, product_id, budget, start_date, end_date),
                ).fetchone()
        if purchase["status"] == "PLACED":
            return ToolResult(decision="ALLOWED", reason="위임 한도 안입니다", status="PLACED",
                              order_id=str(purchase["order_id"]))
        if purchase["status"] == "FAILED":
            return ToolResult(decision="ALLOWED", reason=f"주문이 거절됐습니다: {purchase['failure_reason']}",
                              status="FAILED")
        return self._submit_purchase(purchase)

    def _submit_purchase(self, p: dict) -> ToolResult:
        """주문 API는 잠금 밖에서 부른다. 멱등키를 구매 ID로 고정해, 다시 불러도 주문은 하나다."""
        try:
            order = self.backend.place_order(p["partner_id"], f"agent:{p['id']}", p["product_id"], p["amount"],
                                             p["start_date"], p["end_date"])
        except BackendError as e:
            with self.pool.connection() as conn:
                conn.execute("UPDATE purchases SET status = 'FAILED', failure_reason = %s WHERE id = %s",
                             (e.detail, p["id"]))
            return ToolResult(decision="ALLOWED", reason=f"주문이 거절됐습니다: {e.detail}", status="FAILED")
        except BackendUnavailable as e:
            # 예약(RESERVED)은 그대로 둔다. 한도에서 빠지지 않으므로 다시 불러도 한도를 넘지 않는다
            raise Retryable(f"주문 서비스가 잠시 응답하지 않습니다({e}). 같은 idempotency_key로 다시 부르세요") from e
        with self.pool.connection() as conn:
            conn.execute("UPDATE purchases SET status = 'PLACED', order_id = %s WHERE id = %s", (order["id"], p["id"]))
        return ToolResult(decision="ALLOWED", reason="위임 한도 안입니다", status="PLACED", order_id=order["id"])

    # ---------- 운영 도구 ----------

    def _ops_campaign(self, operator: str, campaign_id: str) -> dict:
        if _uuid(campaign_id) is None:
            raise NotFound("캠페인 ID 형식이 아닙니다")
        try:
            return self.backend.ops_campaign(operator, campaign_id)
        except BackendError as e:
            raise NotFound(e.detail) from e
        except BackendUnavailable as e:
            raise Retryable(f"캠페인 서비스가 잠시 응답하지 않습니다({e})") from e

    def quote_refund(self, operator: str, campaign_id: str) -> dict:
        """환불 견적 = 예산 − campaign이 아는 소진액. 소진액은 delivery보다 늦게 따라오므로 실제 환불액은 이 값보다 크지 않다."""
        view = self._ops_campaign(operator, campaign_id)
        return {"campaign_id": campaign_id, "status": view["status"], "budget": view["budget"], "spent": view["spent"],
                "refund_quote": max(view["budget"] - view["spent"], 0), "approval_threshold": REFUND_APPROVAL_THRESHOLD}

    def request_refund(self, operator: str, campaign_id: str, key: str) -> ToolResult:
        caller = f"operator:{operator}"
        return self._write_tool(caller, None, "request_refund", key, {"campaign_id": campaign_id},
                                lambda: self._refund(operator, caller, campaign_id, key))

    def _refund(self, operator: str, caller: str, campaign_id: str, key: str) -> ToolResult:
        try:
            quote = self.quote_refund(operator, campaign_id)
        except NotFound as e:
            return ToolResult(decision="DENIED", reason=f"캠페인을 확인할 수 없습니다: {e}", status="NOT_EXECUTED")
        decision = decide_refund(quote["status"], quote["refund_quote"], REFUND_APPROVAL_THRESHOLD)
        if decision.kind == "DENIED":
            return ToolResult(decision="DENIED", reason=decision.reason, status="NOT_EXECUTED")
        if decision.kind == "APPROVAL_REQUIRED":
            with self.pool.connection() as conn:
                approval_id = self._create_approval(conn, "REFUND", None, caller, key,
                                                    {"campaign_id": campaign_id, "refund_quote": quote["refund_quote"]},
                                                    decision.reason)
            return ToolResult(decision="APPROVAL_REQUIRED", reason=decision.reason, status="PENDING_APPROVAL",
                              approval_id=approval_id, refund_quote=quote["refund_quote"])
        try:
            self.backend.request_refund(operator, campaign_id, f"agent:{caller}:{key}")
        except BackendError as e:
            return ToolResult(decision="ALLOWED", reason=f"환불 요청이 거절됐습니다: {e.detail}", status="FAILED",
                              refund_quote=quote["refund_quote"])
        except BackendUnavailable as e:
            raise Retryable(f"캠페인 서비스가 잠시 응답하지 않습니다({e}). 같은 idempotency_key로 다시 부르세요") from e
        return ToolResult(decision="ALLOWED", reason=decision.reason, status="REFUND_REQUESTED",
                          refund_quote=quote["refund_quote"])

    # ---------- 승인(사람만, REST로) ----------

    @staticmethod
    def _visible(actor: str) -> tuple[str, str | None]:
        """파트너는 자기 구매 승인만, 운영자는 환불 승인만 본다."""
        kind, _, ident = actor.partition(":")
        return ("PURCHASE", ident) if kind == "partner" else ("REFUND", None)

    def approvals(self, actor: str, status: str | None = None) -> list[dict]:
        kind, partner_id = self._visible(actor)
        with self.pool.connection() as conn:
            return conn.execute(
                "SELECT * FROM approvals WHERE kind = %s AND (%s::text IS NULL OR partner_id = %s)"
                " AND (%s::text IS NULL OR status = %s) ORDER BY created_at DESC LIMIT 50",
                (kind, partner_id, partner_id, status, status),
            ).fetchall()

    def _claim(self, approval_id: str, actor: str, to_status: str) -> dict:
        kind, partner_id = self._visible(actor)
        with self.pool.connection() as conn:
            row = conn.execute(
                "SELECT * FROM approvals WHERE id = %s AND kind = %s AND (%s::text IS NULL OR partner_id = %s) FOR UPDATE",
                (_uuid(approval_id), kind, partner_id, partner_id),
            ).fetchone()
            if row is None:
                raise NotFound(f"승인 요청 {approval_id} 이(가) 없습니다")
            if row["status"] == "PENDING":
                row = conn.execute(
                    "UPDATE approvals SET status = %s, decided_by = %s, decided_at = now() WHERE id = %s RETURNING *",
                    (to_status, actor, row["id"]),
                ).fetchone()
                self._audit_in(conn, actor, row, to_status)
        return row

    @staticmethod
    def _audit_in(conn: Connection, actor: str, row: dict, decision: str, result: Any = None) -> None:
        conn.execute(
            "INSERT INTO audit_log (caller, mandate_id, tool, input, decision, reason, result) VALUES (%s, %s, %s, %s, %s, %s, %s)",
            (actor, _uuid(row["payload"].get("mandate_id")), "approval", Jsonb({"approval_id": str(row["id"]), "kind": row["kind"]}),
             decision, row["reason"], None if result is None else Jsonb(result)),
        )

    def reject(self, approval_id: str, actor: str) -> dict:
        return self._claim(approval_id, actor, "REJECTED")

    def approve(self, approval_id: str, actor: str) -> dict:
        """PENDING → APPROVED(실행 중)로 먼저 바꿔 두 번 실행되지 않게 하고, 업무 API는 승인 ID로 만든 멱등키로 부른다."""
        row = self._claim(approval_id, actor, "APPROVED")
        if row["status"] != "APPROVED":
            return row  # 이미 실행·거절·실패로 끝났다
        p = row["payload"]
        idem = f"agent-approval:{row['id']}"
        try:
            if row["kind"] == "PURCHASE":
                result = self.backend.place_order(row["partner_id"], idem, p["product_id"], p["budget"],
                                                  date.fromisoformat(p["start_date"]), date.fromisoformat(p["end_date"]))
            else:
                result = self.backend.request_refund(actor.partition(":")[2], p["campaign_id"], idem)
            status = "EXECUTED"
        except BackendError as e:
            result, status = {"error": e.detail}, "FAILED"
        except BackendUnavailable as e:
            raise Retryable(f"업무 서비스가 잠시 응답하지 않습니다({e}). 다시 승인하면 이어서 실행합니다") from e
        with self.pool.connection() as conn:
            row = conn.execute(
                "UPDATE approvals SET status = %s, result = %s WHERE id = %s AND status = 'APPROVED' RETURNING *",
                (status, Jsonb(result), row["id"]),
            ).fetchone() or row
            self._audit_in(conn, actor, row, status, result)
        return row

    def audit_log(self, limit: int = 50) -> list[dict]:
        with self.pool.connection() as conn:
            return conn.execute("SELECT * FROM audit_log ORDER BY id DESC LIMIT %s", (min(limit, 200),)).fetchall()
