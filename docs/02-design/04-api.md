# 4. API 명세

외부 요청은 모두 gateway(8080)를 거친다. 인증은 `X-Api-Key` 헤더(파트너 키·운영 키). gateway가 키를 확인하고 `X-Partner-Id`·`X-Role`을 붙여 넘긴다. 오류 응답 형식은 RFC 9457(Problem Details)이다.

## 4.1 M1 범위

| 메서드·경로(gateway 기준) | 서비스 | 설명 | 참조 |
| --- | --- | --- | --- |
| GET /api/catalog/products | catalog | 광고 상품 목록 | FR-101 |
| GET /api/catalog/products/{id} | catalog | 상품 상세 | FR-101 |
| POST /api/orders | campaign | 광고 주문 생성. `Idempotency-Key` 필수. 202 + 주문(PAYMENT_PENDING) | FR-102, S1·S2 |
| GET /api/orders/{id} | campaign | 주문 상태 조회 | FR-103 |
| GET /api/campaigns | campaign | 내 캠페인 목록 | FR-105 |
| GET /api/payments?orderId= | payment | 주문의 결제 상태 | FR-201 |

주문 생성 요청

```json
{ "productId": 1, "budget": 300000, "startDate": "2026-12-01", "endDate": "2026-12-07" }
```

| 응답 | 경우 |
| --- | --- |
| 202 | 주문 접수(결제는 비동기) |
| 200 | 같은 키·같은 본문의 재요청(처음 응답 그대로) |
| 400 | 예산 부족·기간 초과·없는 상품 |
| 409 | 같은 키·다른 본문 |
| 503 | catalog 조회 불가(서킷 열림). `Retry-After` 포함 |

## 4.2 mock-pg (외부 PG 대역)

| 메서드·경로 | 설명 |
| --- | --- |
| POST /v1/payments | 승인. `Idempotency-Key` = 결제 ID. 같은 키는 같은 결과 |
| GET /v1/payments/{idempotencyKey} | 결과 조회(UNKNOWN 확정용) |
| POST /v1/payments/{pgTransactionId}/cancel | 취소(부분 금액) |
| PUT /admin/chaos | 지연(ms)·실패율·타임아웃 비율 설정 |

## 4.3 이후 단계 (요약)

| 단계 | API |
| --- | --- |
| M3 | POST /api/impressions(대량 수집), GET /api/campaigns/{id}/stats, GET /api/settlements?partnerId=&month= |
| M4 | GET /api/screens/{screenId}(SDUI 스키마, `X-Client-Components` 헤더로 지원 컴포넌트·버전 전달), PUT /admin/screens/{screenId} |
| M5 | MCP 도구: `/api/agent/mcp/partner`(list_products, get_my_mandate, place_order, list_my_campaigns), `/api/admin/agent/mcp/ops`(get_settlement, quote_refund, request_refund). 사람용 REST: POST·GET /api/agent/mandates, POST /api/agent/mandates/{id}/revoke, GET /api/agent/approvals?status=PENDING, POST /api/agent/approvals/{id}/approve·reject(파트너: 구매 승인), 같은 승인 API를 /api/admin/agent 아래(운영자: 환불 승인), GET /api/admin/agent/audit. 운영 환불 API(내부): POST /ops/campaigns/{id}/refund. 에이전트 키는 MCP 경로 두 곳만 통과한다(ADR-0010) |
