"""업무 서비스 호출. agent는 gateway 뒤(내부망)에서 각 서비스를 직접 부르고, 신원 헤더는 gateway가 붙인 값을 넘긴다."""

import os
from datetime import date
from typing import Any

import httpx2


class BackendError(Exception):
    """업무 서비스가 요청을 거절했다(4xx). 같은 요청을 다시 보내도 결과가 같다."""

    def __init__(self, status: int, detail: str):
        super().__init__(f"{status} {detail}")
        self.status = status
        self.detail = detail


class BackendUnavailable(Exception):
    """연결 실패·시간 초과·5xx. 같은 멱등키로 다시 부르면 이어서 처리된다."""


class Backend:
    def __init__(self, client: httpx2.Client | None = None, urls: dict[str, str] | None = None):
        self.client = client or httpx2.Client(timeout=5.0)
        self.urls = urls or {
            name: os.environ.get(f"{name.upper()}_URL", f"http://localhost:{port}")
            for name, port in (("catalog", 8081), ("campaign", 8082), ("settlement", 8085))
        }

    def _call(self, method: str, service: str, path: str, **kwargs: Any) -> Any:
        try:
            response = self.client.request(method, self.urls[service] + path, **kwargs)
        except httpx2.TransportError as e:
            raise BackendUnavailable(f"{service} 연결 실패: {type(e).__name__}") from e
        if response.status_code >= 500:
            raise BackendUnavailable(f"{service} {response.status_code}")
        if response.status_code >= 400:
            try:
                detail = response.json().get("detail") or response.text
            except ValueError:
                detail = response.text
            raise BackendError(response.status_code, detail)
        return response.json()

    def products(self) -> list[dict]:
        return self._call("GET", "catalog", "/products")

    def place_order(self, partner_id: str, key: str, product_id: int, budget: int, start: date, end: date) -> dict:
        body = {"productId": product_id, "budget": budget, "startDate": str(start), "endDate": str(end)}
        return self._call(
            "POST", "campaign", "/orders", json=body, headers={"X-Partner-Id": partner_id, "Idempotency-Key": key}
        )

    def campaigns(self, partner_id: str) -> list[dict]:
        return self._call("GET", "campaign", "/campaigns", headers={"X-Partner-Id": partner_id})

    def ops_campaign(self, operator: str, campaign_id: str) -> dict:
        return self._call("GET", "campaign", f"/ops/campaigns/{campaign_id}", headers={"X-Operator-Id": operator})

    def request_refund(self, operator: str, campaign_id: str, key: str) -> dict:
        return self._call(
            "POST",
            "campaign",
            f"/ops/campaigns/{campaign_id}/refund",
            headers={"X-Operator-Id": operator, "Idempotency-Key": key},
        )

    def settlement(self, partner_id: str, month: str) -> dict:
        return self._call("GET", "settlement", "/settlements", params={"month": month}, headers={"X-Partner-Id": partner_id})
