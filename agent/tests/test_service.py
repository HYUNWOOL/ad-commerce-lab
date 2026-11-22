"""도구 흐름: 위임 한도(동시 호출 포함), 멱등, 일시 오류 뒤 재개, 승인, 환불, 감사 로그."""

import uuid
from concurrent.futures import ThreadPoolExecutor
from datetime import UTC, date, datetime, timedelta

import psycopg
import pytest

from app.service import NotFound, Rejected, Retryable

P = "P-1"
START = date(2026, 12, 1)


def make_mandate(service, partner=P, expires=timedelta(days=7)) -> str:
    """1회 50만 원, 7일 100만 원, 상품 1·2."""
    return str(service.create_mandate(partner, 500_000, 1_000_000, 7, [1, 2], datetime.now(UTC) + expires)["id"])


def order(service, mandate_id, key, budget=300_000, product=1, partner=P):
    return service.place_order(partner, mandate_id, product, budget, START, 7, key)


def test_within_limits_places_once_and_replays(service, fake):
    m = make_mandate(service)
    first, again = order(service, m, "k1"), order(service, m, "k1")
    assert (first.decision, first.status, first.replayed) == ("ALLOWED", "PLACED", False)
    assert again.replayed and again.order_id == first.order_id
    assert len(fake.order_calls) == 1 and fake.order_calls[0].startswith("agent:")
    assert fake.orders[fake.order_calls[0]]["endDate"] == "2026-12-07"


def test_same_key_with_other_args_is_rejected(service):
    m = make_mandate(service)
    order(service, m, "k1")
    with pytest.raises(Rejected):
        order(service, m, "k1", budget=200_000)


def test_over_per_order_limit_needs_the_partners_approval(service, fake):
    m = make_mandate(service)
    result = order(service, m, "big", budget=900_000)
    assert (result.decision, result.status) == ("APPROVAL_REQUIRED", "PENDING_APPROVAL")
    assert fake.order_calls == []
    for stranger in ("partner:P-2", "operator:ops-1"):  # 다른 파트너도 운영자도 이 승인을 결정할 수 없다
        with pytest.raises(NotFound):
            service.approve(result.approval_id, stranger)
    done, twice = service.approve(result.approval_id, f"partner:{P}"), service.approve(result.approval_id, f"partner:{P}")
    assert done["status"] == twice["status"] == "EXECUTED"
    assert fake.order_calls == [f"agent-approval:{result.approval_id}"]


def test_rejected_approval_never_executes(service, fake):
    m = make_mandate(service)
    result = order(service, m, "big", budget=900_000)
    assert service.reject(result.approval_id, f"partner:{P}")["status"] == "REJECTED"
    assert service.approve(result.approval_id, f"partner:{P}")["status"] == "REJECTED"
    assert fake.order_calls == []


def test_concurrent_orders_never_exceed_the_period_limit(service, fake):
    """30만 원 구매 6건을 동시에 보내도 기간 한도 100만 원 안의 3건만 바로 주문되고, 나머지는 승인을 기다린다."""
    m = make_mandate(service)
    with ThreadPoolExecutor(6) as pool:
        results = list(pool.map(lambda i: order(service, m, f"c{i}"), range(6)))
    assert sorted(r.status for r in results) == ["PENDING_APPROVAL"] * 3 + ["PLACED"] * 3
    assert service.my_mandate(P, m)["used_in_period"] == 900_000
    assert len(fake.order_calls) == 3


def test_unavailable_keeps_the_reservation_and_a_retry_resumes(service, fake):
    m = make_mandate(service)
    fake.down = True
    with pytest.raises(Retryable):
        order(service, m, "k1")
    assert service.my_mandate(P, m)["used_in_period"] == 300_000  # 예약이 남아 그사이 다른 구매가 한도를 넘지 못한다
    fake.down = False
    result = order(service, m, "k1")
    assert (result.status, result.replayed) == ("PLACED", False)
    assert len(fake.orders) == 1


def test_a_rejected_order_releases_the_reservation(service, fake):
    m = make_mandate(service)
    fake.reject_orders = True
    result = order(service, m, "k1")
    assert (result.decision, result.status) == ("ALLOWED", "FAILED")
    assert service.my_mandate(P, m)["used_in_period"] == 0


@pytest.mark.parametrize("case", ["no_mandate", "other_partner", "revoked", "expired", "product"])
def test_denied_without_calling_campaign(service, fake, case):
    m = make_mandate(service, expires=timedelta(seconds=-1) if case == "expired" else timedelta(days=7))
    if case == "revoked":
        service.revoke_mandate(P, m)
    result = service.place_order("P-2" if case == "other_partner" else P, None if case == "no_mandate" else m,
                                 3 if case == "product" else 1, 100_000, START, 7, "k")
    assert (result.decision, result.status) == ("DENIED", "NOT_EXECUTED")
    assert fake.order_calls == []


def campaign(fake, budget, spent, status="ACTIVE") -> str:
    campaign_id = str(uuid.uuid4())
    fake.campaigns[campaign_id] = {"id": campaign_id, "budget": budget, "spent": spent, "status": status}
    return campaign_id


def test_small_refund_runs_and_a_large_one_waits_for_an_operator(service, fake):
    small, large = campaign(fake, 300_000, 250_000), campaign(fake, 300_000, 120_000)  # 견적 5만 원, 18만 원
    assert service.request_refund("ops-1", small, "r1").status == "REFUND_REQUESTED"
    pending = service.request_refund("ops-1", large, "r2")
    assert (pending.status, pending.refund_quote) == ("PENDING_APPROVAL", 180_000)
    assert [c for c, _ in fake.refund_calls] == [small]
    with pytest.raises(NotFound):  # 파트너는 환불 승인을 결정할 수 없다
        service.approve(pending.approval_id, f"partner:{P}")
    assert service.approve(pending.approval_id, "operator:ops-2")["status"] == "EXECUTED"
    assert fake.refund_calls[-1] == (large, f"agent-approval:{pending.approval_id}")


@pytest.mark.parametrize("target", ["refunded", "missing", "not-a-uuid"])
def test_refund_denied(service, fake, target):
    campaign_id = {"refunded": lambda: campaign(fake, 300_000, 0, status="REFUNDED"),
                   "missing": lambda: str(uuid.uuid4()), "not-a-uuid": lambda: "../orders"}[target]()
    result = service.request_refund("ops-1", campaign_id, "r")
    assert (result.decision, result.status) == ("DENIED", "NOT_EXECUTED")
    assert fake.refund_calls == []


def test_every_call_is_audited_and_the_log_is_append_only(service, pool):
    m = make_mandate(service)
    order(service, m, "k1")
    order(service, m, "k1")
    order(service, m, "big", budget=900_000)
    rows = service.audit_log()
    assert [r["decision"] for r in rows] == ["APPROVAL_REQUIRED", "ALLOWED", "ALLOWED"]
    assert (str(rows[-1]["mandate_id"]), rows[-1]["caller"], rows[-1]["tool"]) == (m, f"partner:{P}", "place_order")
    for sql in ("UPDATE audit_log SET decision = 'DENIED'", "DELETE FROM audit_log"):
        with pytest.raises(psycopg.errors.RaiseException), pool.connection() as conn:
            conn.execute(sql)
