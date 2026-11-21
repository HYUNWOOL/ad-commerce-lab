# agent-checkout

AI 쇼핑 에이전트가 주문·결제해도 금액과 상태는 틀리지 않는 커머스 결제 플랫폼. 이직 포트폴리오용. 기획은 docs/PLAN.md.

## 진행 방식
- 답변·보고는 한국어로만 쓴다(코드·명령·고유 명사는 원문).
- 설계는 AI가 결정하고 진행한다. 대신 결정마다 docs/decisions.md에 "상황 · 선택지 · 결정 · 이유 · 포기한 것"을 한 단락으로 남긴다. 주인이 면접에서 설명할 수 있어야 한다.
- 단계(M1~M4)가 끝나면 docs/flow-mN.md에 요청 하나가 지나는 흐름을 mermaid 그림 한 장과 실제 실행 로그로 남긴다.
- 완료는 테스트·실행 로그·측정 숫자로 판정한다. "됐다"는 말로 끝내지 않는다.
- 커밋은 단계 안에서 의미 단위로 한다. push는 단계가 끝났을 때.

## 코드 규칙
- 돈은 원 단위 정수(Long). 부동소수점 금지.
- 쓰기 API는 Idempotency-Key 필수. 같은 키 + 다른 본문은 409.
- 상태 변경과 이벤트 발행은 같은 트랜잭션(outbox). 소비는 inbox로 멱등.
- SQL은 직접(Spring JdbcClient / psycopg). 스키마는 Flyway(Kotlin) · Alembic(Python)으로만.
- 통합 테스트는 Testcontainers로 실제 Postgres·Kafka에 붙인다.
- 라이브러리 API는 기억이 아니라 공식 문서로 확인한다. 버전은 빌드 파일에 고정한다.
- 시크릿은 .env에만, 커밋하지 않는다. .env를 읽거나 출력하지 않는다.
- 외부 호출에는 타임아웃을 반드시 둔다. LLM 실험에는 호출 횟수 상한을 둔다.

## 환경
- Windows, JDK 25, Node 24, Docker Desktop, gh(HYUNWOOL). Gradle은 wrapper로.
- 경로에 한글이 없다(C:\Users\kiesl\dev\agent-checkout).
