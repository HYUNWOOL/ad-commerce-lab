"""에이전트 정책(ADR-0010 2번). 모델이 보낸 인자는 "요청"일 뿐이고, 실행 여부는 여기서 정한다.

DB도 네트워크도 모르는 순수 함수다. 같은 입력이면 늘 같은 결정을 낸다.
"""

from dataclasses import dataclass
from datetime import datetime
from typing import Literal

DecisionKind = Literal["ALLOWED", "APPROVAL_REQUIRED", "DENIED"]


@dataclass(frozen=True)
class Decision:
    kind: DecisionKind
    reason: str


@dataclass(frozen=True)
class Mandate:
    """파트너가 에이전트에게 준 위임(FR-602). 금액은 원 단위 정수."""

    id: str
    partner_id: str
    per_order_limit: int
    period_limit: int
    period_days: int
    allowed_product_ids: frozenset[int]
    expires_at: datetime
    revoked: bool


REFUNDABLE_STATUSES = frozenset({"ACTIVE", "EXHAUSTED"})


def decide_purchase(
    mandate: Mandate | None, partner_id: str, product_id: int, budget: int, used_in_period: int, now: datetime
) -> Decision:
    """구매: 위임이 유효하고 허용 상품이면, 1회·기간 한도 안에서만 바로 실행한다. 한도를 넘으면 사람의 승인이 필요하다."""
    if mandate is None or mandate.partner_id != partner_id:
        return Decision("DENIED", "이 파트너의 위임을 찾을 수 없습니다")
    if mandate.revoked:
        return Decision("DENIED", "철회된 위임입니다")
    if now >= mandate.expires_at:
        return Decision("DENIED", "만료된 위임입니다")
    if product_id not in mandate.allowed_product_ids:
        return Decision("DENIED", f"위임에서 허용하지 않은 상품입니다(상품 {product_id})")
    if budget <= 0:
        return Decision("DENIED", "예산은 0원보다 커야 합니다")
    if budget > mandate.per_order_limit:
        return Decision("APPROVAL_REQUIRED", f"1회 한도 {mandate.per_order_limit:,}원을 넘습니다({budget:,}원)")
    if used_in_period + budget > mandate.period_limit:
        return Decision(
            "APPROVAL_REQUIRED",
            f"{mandate.period_days}일 한도 {mandate.period_limit:,}원을 넘습니다(이미 {used_in_period:,}원 + 이번 {budget:,}원)",
        )
    return Decision("ALLOWED", "위임 한도 안입니다")


def decide_refund(campaign_status: str, refundable: int, approval_threshold: int) -> Decision:
    """환불: 환불할 수 있는 상태인지, 서버가 계산한 금액이 기준 이상인지만 본다. 금액은 모델이 정하지 않는다."""
    if campaign_status not in REFUNDABLE_STATUSES:
        return Decision("DENIED", f"{campaign_status} 상태의 캠페인은 환불할 수 없습니다")
    if refundable >= approval_threshold:
        return Decision("APPROVAL_REQUIRED", f"환불 예상액 {refundable:,}원이 승인 기준 {approval_threshold:,}원 이상입니다")
    return Decision("ALLOWED", f"환불 예상액 {refundable:,}원은 승인 기준 아래입니다")
