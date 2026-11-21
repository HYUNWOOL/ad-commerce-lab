# M3 작업 계획과 진행 상황

GitHub Milestone "M3 노출·과금·정산"(Issue #12~#14). 설계 근거는 ADR-0007(노출 과금), ADR-0008(원장).

## 진행 상황

| Issue | 작업 | 상태 |
| --- | --- | --- |
| #12 | delivery(8084): 수집 API → Kafka → 배치 집계·과금, 예산 원본, CampaignCharged·BudgetExhausted. campaign이 사용액 복제. 통합 테스트 7개(+campaign 1개) | 완료 |
| #13 | settlement(8085): 복식부기 원장(DB 트리거로 균형·추가 전용 강제), 월 정산서 API. 통합 테스트 6개 | 완료 |
| #14 | k6 노출 부하, 중복·재시작·예산 소진 실험, 스모크 `scripts/e2e-m3.sh` | 완료 — [결과](../04-verification/m3-billing.md), [흐름](flow-m3.md) |

## 실험하면서 바뀐 설계
| 발견 | 바꾼 것 |
| --- | --- |
| S7: 과금은 따라갔지만 배치가 작아 과금 이벤트가 노출 9.4건당 1건(3만 건), 원장이 148초 늦음 | 배치 채우기(최대 500ms), Outbox 릴레이 일괄 발행 → 86건당 1건, 원장 5초(ADR-0007, ADR-0002) |
| S7: DB만으로 초당 1만 건에서 과금 지연 p95 0.2초 | Redis 넣지 않음(ADR-0007) |
| S9: 강제 종료 뒤 파티션을 39초 뒤에야 다시 받음(세션 타임아웃) | 정적 멤버십 → 0.02초(ADR-0007) |
| 공통: Spring Kafka 기본 처리기는 10번 실패하면 메시지를 버림 | 무한 재시도 + 깨진 JSON만 건너뜀(모든 서비스) |

## 목표 숫자 (NFR-5, 실측 전에 정함)
| 항목 | 목표 |
| --- | --- |
| 수집 처리량 | 초당 5,000건을 60초 동안 버틴다 |
| 수집 API p95 | 200ms 이하(요청 하나에 100건) |
| 과금 지연(노출 발생 → 과금) p95 | 2초 이하 |
| 과금 정확도 | 중복 20%·처리 중 재시작에도 과금한 노출 수 = 처음 본 노출 수, 차이 0 |
| 예산 | 어떤 경우에도 사용액 ≤ 예산, 원장 사용액 = delivery 사용액 |

## 정해 둔 것
- 마이그레이션: delivery·settlement는 `V5__...`(db/common V1·V3 다음).
- 이벤트 추가: `PaymentApproved`에 `partnerId`(필드 추가라 version 1 유지), `CampaignCharged`는 `chargeId` 기준(시간 창 대신 배치).
- 공통 Kafka 오류 처리기: 실패한 메시지를 버리지 않고 1초→10초 간격 무한 재시도(모든 서비스).
- gateway: `/api/campaigns/*/stats` → delivery(캠페인 경로보다 먼저), `/api/settlements` → settlement. 수집 API는 gateway에 열지 않는다.
