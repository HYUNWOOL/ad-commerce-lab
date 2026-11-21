# M2 작업 계획과 진행 상황

GitHub Milestone "M2 장애 격리·관측"(Issue #9~#11). 설계 근거는 ADR-0003(수정), ADR-0004, ADR-0005.

## 진행 상황

| Issue | 작업 | 상태 |
| --- | --- | --- |
| #9 | PG 재조회 스케줄러(UNKNOWN·오래된 REQUESTED → 조회 → 없으면 같은 멱등키로 재승인), 승인·조회 서킷 분리, 스케줄러 스레드 분리, 컨슈머 동시성 3, PG가 받지 않은 결제 보류(ADR-0006). 통합 테스트 11개 | 완료 |
| #10 | Toxiproxy(PG·catalog 링크), k6, 실험 스크립트 `scripts/experiments/m2.sh`, S4~S6 전후 숫자 | 완료 — [결과](../04-verification/m2-resilience.md) |
| #11 | OpenTelemetry → Jaeger(v3 API), Prometheus, Grafana 대시보드, Outbox 트레이스 이어 붙이기, `scripts/trace.py` | 완료 — [흐름 기록](flow-m2.md) |

## 실험하면서 바뀐 설계
| 발견 | 바꾼 것 |
| --- | --- |
| S5 첫 실행: 승인 타임아웃 8건이 하나뿐인 `pg` 서킷을 열어, 그 결과를 확인할 조회까지 10초 막힘(확정 p50 22.8초) | 승인 `pg` / 조회 `pg-query` 서킷 분리 → 확정 p50 12.8초, 재조회 1회로 끝(ADR-0004 5번) |
| S4: 서킷을 켜니 장애 중 PG 호출은 79 → 21건이지만 1분 장애에 주문 292건 취소 | PG가 받지 않은 결제는 보류 후 재시도, 10분 기한(ADR-0006) |
| S4: 클라이언트 `records-lag` 메트릭이 0 | 브로커 기준(커밋 오프셋) 지연으로 측정, 대시보드에서 뺌(ADR-0005) |
| 트레이스가 `/actuator`·`@Scheduled` 빈 트레이스에 묻힘 | 관측 필터(ObservationPredicate) |
| Jaeger 2.23은 옛 `/api/traces`가 없음 | `/api/v3` 사용 |

## 정해 둔 것

### 마이그레이션 번호
Flyway 이력은 서비스 DB마다 하나라 `db/common`과 서비스 `db/migration`이 버전 번호를 함께 쓴다. 새 번호는 전체에서 가장 큰 번호 다음을 쓴다. M2: `db/common/V3__outbox_trace_parent.sql`, payment `V4__payment_reconcile.sql`.

### 재조회(#9)
- `PgClient.find(paymentId)` 추가: 있음(승인·거절) / 없음 / 장애.
- `PaymentReconciler`: 5초마다, 한 번에 20건. 집기·확정은 짧은 트랜잭션, PG 호출은 그 사이(트랜잭션 밖).
- 타임아웃·대기 시간은 설정값으로 뺀다: `clients.pg.read-timeout`, `clients.catalog.read-timeout`, `payment.reconcile.*`. 실험에서 "보호 없음"을 재현하려고 바꿔야 해서다.

### 실험(#10)
| 실험 | 주입 | 비교 | 재는 것 |
| --- | --- | --- | --- |
| S4 PG 지연·실패 | PG 링크 지연 2.5초 + PG 503 50% | 서킷 끔 / 켬 | 주문 API p95, 조회 API p95, 결제 확정까지 걸린 시간, PG가 받은 호출 수, 컨슈머 지연 |
| S5 결과 불명 | PG가 3.5초 뒤 처리(읽기 타임아웃 3초) / PG 링크 끊김 | — | UNKNOWN 건수, 사람 개입 없이 확정된 건수, 확정까지 시간, 중복 승인 수 |
| S6 catalog 지연 | catalog 링크 지연 3초, campaign 요청 스레드 40개 | 타임아웃 30초·서킷 끔 / 1초·켬 | 주문 API와 무관한 조회 API(`GET /campaigns`)의 p95·오류율 |

부하는 k6(Docker 이미지)로 만든다. "끔"은 서킷 임계값을 100%로 올려 열리지 않게 하는 설정 파일 하나로 바꾼다.

### 관측(#11)
- 공통: `spring-boot-starter-opentelemetry`, `micrometer-registry-prometheus`, 샘플링 1.0, Kafka 관측 켬, OTLP 메트릭 끔.
- RestClient는 Boot가 주는 `RestClient.Builder`로 만든다(정적 `RestClient.builder()`는 계측되지 않는다).
- 대시보드: 서킷 상태, PG 호출(상태 코드별), 컨슈머 지연, 서비스별 HTTP p95, 결제 상태 전이.
