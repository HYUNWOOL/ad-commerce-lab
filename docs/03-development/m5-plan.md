# M5 작업 계획과 진행 상황

GitHub Milestone "M5 AI 에이전트"(Issue #17·#18). 설계 근거는 ADR-0010(에이전트 정책), ADR-0011(부분 환불 Saga).

## 진행 상황

| 단계 | 작업 | 상태 |
| --- | --- | --- |
| 환불 Saga | campaign 운영 환불 API → delivery 과금 닫기·금액 확정 → payment PG 취소(멱등) → campaign REFUNDED · settlement 원장. 통합 테스트 campaign +1, delivery +1, payment +2, settlement +1 | 완료 |
| #17 | agent(Python, 8000): MCP 도구(파트너·운영), 위임 한도(예약·행 잠금), 승인, 멱등, 감사 로그(append-only 트리거). gateway 경로와 에이전트 전용 키, SDUI 승인 화면(experience `ApprovalList` + web). pytest 22개, gateway +1, experience +1, web +1 | 완료 — [흐름](flow-m5.md) |
| #18 | 평가: "모델이 100% 속는다"고 가정한 도구 호출 17케이스를 gateway로 보내 campaign DB에서 실제 실행을 센다 | 완료 — 정책 위반 실행 0건, [결과](../04-verification/m5-agent.md) |

## 도구

| 엔드포인트 | 도구 | 쓰기 | 정책 |
| --- | --- | --- | --- |
| `/mcp/partner` (파트너 키 + `X-Mandate-Id`) | `list_products` | | |
| | `get_my_mandate` | | |
| | `place_order(product_id, budget, start_date, days, idempotency_key)` | O | 위임 만료·허용 상품 → 거절, 1회·기간 한도 초과 → 승인 필요 |
| | `list_my_campaigns` | | |
| `/mcp/ops` (운영자 키) | `get_settlement(partner_id, month)` | | |
| | `quote_refund(campaign_id)` | | 금액은 서버 계산 |
| | `request_refund(campaign_id, idempotency_key)` | O | 10만 원 이상 → 승인 필요, 환불할 수 없는 상태 → 거절 |

승인·거절은 도구가 아니라 REST(`/approvals/{id}/approve`)와 SDUI 승인 화면으로만 한다.

## 정해 둔 것
- 마이그레이션: 전체 다음 번호 V6(catalog 상품 설명, campaign 환불 금액, payment 환불 표). agent는 Alembic, DB `agent`.
- 이벤트: `RefundRequested`(campaign.lifecycle), `RefundAmountFixed`(새 토픽 delivery.budget), `RefundCompleted`(payment.refund).
- mock-pg 취소에 멱등키를 받는다(같은 키는 처음 결과).
- catalog 상품 하나의 설명에 주입 문구를 넣어 둔다(S16 실험용).
- Python: 3.13, uv, mcp 2.3.0(`MCPServer`, `streamable_http_app(json_response=True)`), FastAPI, psycopg 3, Alembic, pytest + testcontainers.

## 만들면서 바뀐 것
| 발견 | 바꾼 것 |
| --- | --- |
| 에이전트에게 사람의 파트너 키를 주면, HTTP를 부를 수 있는 에이전트가 같은 키로 승인 REST를 부를 수 있다 | gateway에 에이전트 전용 키를 두고 MCP 경로만 통과시킨다(ADR-0010 구현 메모) |
| Windows에서 Alembic이 `alembic.ini`를 시스템 기본 인코딩(cp949)으로 읽어 한글 주석에서 깨졌다 | ini에는 ASCII만 두고 설명은 `migrations/env.py`에 쓴다 |
| 위임 행 잠금(`FOR UPDATE`)을 빼고 동시 구매 테스트를 세 번 돌리면 세 번 다 실패한다(한도 초과 주문) | 잠금을 지키는 테스트로 남겼다(`test_concurrent_orders_never_exceed_the_period_limit`) |
| 다른 파트너가 남의 위임 ID와 같은 멱등키를 쓰면 남의 구매 결과를 돌려받을 수 있었다(리뷰에서 발견) | 이어서 처리할 구매를 찾을 때 파트너 ID도 조건에 넣는다 |
| 운영용 캠페인 조회에는 파트너 ID가 없다 | 환불 승인 요청은 파트너 없이 만들고, 운영자만 보고 결정한다 |
