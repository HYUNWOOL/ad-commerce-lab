# M4 작업 계획과 진행 상황

GitHub Milestone "M4 서버 주도 화면"(Issue #15·#16). 설계 근거는 ADR-0009.

## 진행 상황

| Issue | 작업 | 상태 |
| --- | --- | --- |
| #15 | experience(8086): 화면 리비전 저장·검사, 출처별 병렬 조회(타임아웃·서킷), 컴포넌트 버전 맞추기, 영역별 대체 표시. gateway 운영자 키. 통합 테스트 7개 + gateway 필터 테스트 4개 | 완료 |
| #16 | web(5173): React 렌더러, 모르는 컴포넌트·섹션 오류 대체 표시, 구매 폼, "구버전 앱" 흉내. 렌더러 테스트 5개 | 완료 — [결과](../04-verification/m4-sdui.md), [흐름](flow-m4.md) |

## 만들면서 바뀐 것
| 발견 | 바꾼 것 |
| --- | --- |
| Jackson 3의 `JsonNode.map {}`은 원소가 아니라 노드 자신에 함수를 적용한다. Kotlin의 목록 `map`인 줄 알고 썼다가 성과 목록 변환이 배열 전체를 한 원소로 다뤘다(테스트 컴파일 단계에서 발견) | 원소를 돌 때는 `values()`를 쓴다 |
| 실험 스크립트가 한글 JSON을 명령줄 인자로 넘기자 Git Bash가 인코딩을 바꿔 400 | 본문은 표준 입력으로 보낸다 |
| 테스트끼리 같은 화면 설정을 써서, 숨김 테스트가 다른 테스트의 섹션 순서를 바꿨다 | 숨김 테스트가 끝날 때 되돌린다(되돌리기도 리비전) |

## 화면과 컴포넌트

| 화면 | 섹션(기본 순서) |
| --- | --- |
| `home` 파트너 홈 | 공지(Banner) → 이번 달 정산(SettlementSummary ← settlement) → 예산 사용률(BudgetGauge ← delivery, 새 컴포넌트) → 캠페인 성과(CampaignPerformanceList ← delivery) → 상품 보러 가기(ActionButton) |
| `products` 광고 상품 | 환불 안내(Banner) → 상품 목록(ProductList ← catalog) → 구매(PurchaseForm → 주문 API) |

| 컴포넌트 | 버전 | 비고 |
| --- | --- | --- |
| Banner | 1 | text, tone |
| SettlementSummary | 1 | paid, used, balance |
| CampaignPerformanceList | 1, 2 | v2에 노출 수·진행률 추가. v1 클라이언트에는 빼고 보낸다 |
| BudgetGauge | 1 | M4에 새로 넣는 컴포넌트. 모르는 클라이언트에는 Fallback |
| ActionButton | 1 | navigate |
| ProductList | 1 | catalog 상품 |
| PurchaseForm | 1 | 주문 API, 멱등키 |
| Fallback | 1 | 모든 클라이언트가 안다 |

## 정해 둔 것
- 마이그레이션: experience는 `V5__screens.sql`(초기 리비전 1 시드 포함).
- delivery에 `GET /campaigns/stats`(파트너의 최근 캠페인 20개) 추가. 캠페인마다 부르면 N+1이 된다.
- gateway: `/api/screens/**` → experience, `/api/admin/**` → experience(운영자 키만). 데모 운영자 키는 설정 파일에 둔다(실제 비밀값 아님).
- experience → delivery 링크는 Toxiproxy를 거친다(S12 지연 주입).
