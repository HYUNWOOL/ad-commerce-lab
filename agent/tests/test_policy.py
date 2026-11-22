from datetime import UTC, datetime, timedelta

from app.policy import Mandate, decide_purchase, decide_refund

NOW = datetime(2026, 11, 22, tzinfo=UTC)
M = Mandate("m", "P-1", 500_000, 1_000_000, 7, frozenset({1}), NOW + timedelta(days=1), False)


def kind(**overrides) -> str:
    args = {"mandate": M, "partner_id": "P-1", "product_id": 1, "budget": 500_000, "used_in_period": 500_000,
            "now": NOW} | overrides
    return decide_purchase(**args).kind


def test_purchase_boundaries():
    assert kind() == "ALLOWED"  # 1회 한도와 기간 한도에 딱 맞으면 실행한다
    assert kind(budget=500_001) == "APPROVAL_REQUIRED"
    assert kind(used_in_period=500_001) == "APPROVAL_REQUIRED"
    assert kind(budget=0) == kind(budget=-1) == "DENIED"
    assert kind(now=M.expires_at) == "DENIED"
    assert kind(product_id=2) == "DENIED"
    assert kind(partner_id="P-2") == "DENIED"
    assert kind(mandate=None) == "DENIED"


def test_refund_boundaries():
    assert decide_refund("ACTIVE", 99_999, 100_000).kind == "ALLOWED"
    assert decide_refund("EXHAUSTED", 100_000, 100_000).kind == "APPROVAL_REQUIRED"
    assert decide_refund("REFUND_REQUESTED", 0, 100_000).kind == "DENIED"
