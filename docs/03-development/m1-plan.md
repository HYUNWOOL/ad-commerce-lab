# M1 작업 계획과 진행 상황

GitHub Milestone "M1 구매·결제 코어"(Issue #1~#8). 설계 근거는 docs/02-design.

## 진행 상황

| Issue | 작업 | 상태 |
| --- | --- | --- |
| #1 | Gradle 멀티 모듈, common(EventEnvelope, Outbox·OutboxRelay, Inbox, IdempotencyStore, ProblemHandlers, InfraContainers 테스트 픽스처) | 완료(컴파일) |
| #3 | catalog: 상품 조회, Flyway 시드 4개 | 완료(빌드) |
| #5 | mock-pg: 승인·조회·취소, `PUT /admin/chaos`(delayMs, failureRate), 500만 원 초과 거절 | 완료(빌드) |
| #4 | campaign: 주문(멱등키)·상태 머신·Outbox·결제 결과 소비, 통합 테스트 8개 | 완료 |
| #6 | payment: OrderPlaced 소비 → PG(트랜잭션 밖, CB) → 결과 이벤트, UNKNOWN 처리, 통합 테스트 4개 | 완료 |
| #7 | Saga 보상: 결제 실패 → 주문 취소 | 완료(campaign 테스트) |
| #2, #8 | compose 인프라(8개 컨테이너), gateway(API 키·라우팅), E2E 스모크 S1~S3 | 완료 — [흐름 기록](flow-m1.md) |

## 남은 구현 방향 (정해 둔 것)

### 공통 규칙
- 서비스 앱은 `@SpringBootApplication(scanBasePackages = ["lab.adcommerce"])`로 common을 함께 스캔한다.
- Flyway `locations: classpath:db/common,classpath:db/migration`. 서비스 마이그레이션은 V2부터.
- `JdbcClient.query(Class).list()`는 Kotlin에서 `List<T?>`이므로 `filterNotNull()`.
- Jackson 3(`tools.jackson.*`). 이벤트 페이로드는 `mapper.treeToValue(env.payload, X::class.java)`로 data class로 읽는다(`asText` 같은 API 이름에 기대지 않는다).
- 원격 호출은 DB 트랜잭션 밖에서 한다(P1). 트랜잭션은 `TransactionTemplate`으로 범위를 명시한다.
- 타임아웃: `JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(500ms))` + `setReadTimeout`. 서킷 브레이커는 `CircuitBreakerRegistry.circuitBreaker(name).executeSupplier { }`(AOP 없이 명시적으로).
- `spring.kafka.consumer.auto-offset-reset: earliest`. 토픽은 생산 서비스가 `TopicBuilder`로 3 파티션 생성.

### campaign (8082, DB campaign)
- 테이블: `ad_orders`(id UUID, partner_id, product_id, budget, cpv_price 스냅샷, start/end_date, status, failure_reason), `campaigns`(id, order_id UNIQUE, partner_id, product_id, budget, spent, cpv_price, start/end, status).
- `POST /orders`(헤더 X-Partner-Id, Idempotency-Key): 멱등 저장 응답이 있으면 바로 반환 → catalog 조회(트랜잭션 밖, CB "catalog", 실패 시 503) → 검증(활성, 최소 예산, 최대 기간) → 트랜잭션 안에서 IdempotencyStore + INSERT + Outbox `OrderPlaced`(토픽 campaign.order, 키 orderId). 새 요청 202, 재요청 200.
- `payment.result` 소비(그룹 campaign): Inbox → `PaymentApproved`면 `UPDATE ... WHERE status='PAYMENT_PENDING'`로 PAID + 캠페인 ACTIVE 생성 + `CampaignActivated`(campaign.lifecycle), `PaymentFailed`면 CANCELLED + 사유.
- 테스트: 주문 202·이벤트 발행, 같은 키 10번 → 주문 1개, 다른 본문 409, 최소 예산 미달 400, PaymentApproved 두 번 → 캠페인 1개, PaymentFailed → 취소, catalog 장애 → 503.

### payment (8083, DB payment)
- 테이블 `payments`(id, order_id UNIQUE, partner_id, amount, status, pg_transaction_id, failure_reason).
- `campaign.order` 소비(그룹 payment): 트랜잭션 1(Inbox + INSERT ON CONFLICT(order_id) DO NOTHING) → 트랜잭션 밖에서 PG 승인(멱등키 = 결제 ID, CB "pg", 읽기 3초) → 트랜잭션 2(상태 전이 + Outbox).
- 결과 매핑: 승인 → APPROVED + PaymentApproved, 거절 → FAILED + PaymentFailed(DECLINED), 503·연결 실패·서킷 열림 → FAILED + PaymentFailed(PG_UNAVAILABLE), 읽기 타임아웃 → UNKNOWN(이벤트 없음, M2에서 재조회).
- 테스트(PgClient 스텁): 승인 흐름, 같은 이벤트 두 번 → PG 1번, 같은 주문 다른 이벤트 → 결제 1건, 거절, 타임아웃 → UNKNOWN.

### 인프라·gateway·E2E
- infra/docker-compose.yml: postgres:17.12-alpine(init으로 DB catalog·campaign·payment 생성, 사용자 lab), apache/kafka:4.3.2(KRaft), kafbat/kafka-ui:v1.5.1, 서비스는 `infra/service.Dockerfile`(eclipse-temurin:25-jre, ARG SERVICE, -Xmx256m).
- gateway(Spring Cloud Gateway Server Web MVC, Spring Cloud 2025.1.5): 라우팅 + API 키 → X-Partner-Id. 설정 키는 구현 전에 문서로 확인.
- scripts/e2e-m1: S1(승인 → 캠페인 ACTIVE), S2(같은 키 10번 → 주문 1건), S3(600만 원 → 거절 → 주문 CANCELLED).
