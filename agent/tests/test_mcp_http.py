"""HTTP로 MCP를 부를 때: 신원은 gateway가 붙인 헤더로만 정하고, 브라우저 Origin·낯선 Host는 받지 않는다."""

from datetime import UTC, datetime, timedelta

import anyio
import httpx2
from mcp import Client
from mcp.client.streamable_http import streamable_http_client

from app.main import create_app

BASE = "http://agent:8000"


def call(service, path: str, headers: dict, tool: str, args: dict):
    app = create_app(service, ["agent:8000"])  # 세션 매니저는 한 번만 돌 수 있어 호출마다 앱을 만든다

    async def run():
        async with app.router.lifespan_context(app):
            http = httpx2.AsyncClient(transport=httpx2.ASGITransport(app=app), headers=headers)
            async with Client(streamable_http_client(BASE + path, http_client=http)) as client:
                return await client.call_tool(tool, args)

    return anyio.run(run)


def raw_post(service, headers: dict, base: str = BASE) -> int:
    app = create_app(service, ["agent:8000"])
    body = {"jsonrpc": "2.0", "id": 1, "method": "tools/list"}

    async def run():
        async with app.router.lifespan_context(app):
            async with httpx2.AsyncClient(transport=httpx2.ASGITransport(app=app), base_url=base) as http:
                response = await http.post("/mcp/partner", json=body,
                                           headers={"Accept": "application/json, text/event-stream", **headers})
                return response.status_code

    return anyio.run(run)


def test_partner_identity_comes_from_the_gateway_header(service, fake):
    m = str(service.create_mandate("P-1", 500_000, 1_000_000, 7, [1], datetime.now(UTC) + timedelta(days=1))["id"])
    result = call(service, "/mcp/partner", {"X-Partner-Id": "P-1", "X-Mandate-Id": m}, "place_order",
                  {"product_id": 1, "budget": 300_000, "start_date": "2026-12-01", "days": 7, "idempotency_key": "k1"})
    assert not result.is_error, result.content
    assert result.structured_content["status"] == "PLACED"
    assert [o["partnerId"] for o in fake.orders.values()] == ["P-1"]


def test_tools_refuse_a_connection_without_the_right_identity(service, fake):
    no_identity = call(service, "/mcp/partner", {}, "list_products", {})
    partner_on_ops = call(service, "/mcp/ops", {"X-Partner-Id": "P-1"}, "request_refund",
                          {"campaign_id": "00000000-0000-0000-0000-000000000001", "idempotency_key": "r1"})
    assert no_identity.is_error and partner_on_ops.is_error
    assert "운영자 신원" in partner_on_ops.content[0].text
    assert fake.refund_calls == []


def test_browser_origin_and_unknown_host_are_refused(service):
    assert raw_post(service, {"X-Partner-Id": "P-1"}) == 200
    assert raw_post(service, {"X-Partner-Id": "P-1", "Origin": "https://evil.example"}) == 403
    assert raw_post(service, {"X-Partner-Id": "P-1"}, base="http://evil.example") == 421
