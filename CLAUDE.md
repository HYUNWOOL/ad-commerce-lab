# ad-commerce-lab

현업 불편 세 가지(외부 PG 장애 전파, 앱 배포에 묶인 화면 변경, 개발자에게 오는 운영 요청)를 가상의 광고 커머스 플랫폼 "Nearby"에서 MSA로 만들어 검증하는 실험 프로젝트. 이직 포트폴리오용. 문서 지도는 docs/README.md.

## 진행 방식
- 답변·보고는 한국어로만 쓴다(코드·명령·고유 명사는 원문).
- 기획(docs/01-planning) → 설계(02-design) → 개발(03-development) → 검증(04-verification) 순서를 지킨다. 뒤 단계 문서는 앞 단계 번호(시나리오 S, 요구사항 FR·NFR)를 참조한다.
- 개발 작업은 GitHub Milestone(M1~M5)과 Issue로 관리하고, 커밋 메시지에 Issue 번호를 단다.
- 설계는 AI가 결정하고 진행한다. 대신 결정마다 ADR(docs/02-design/adr)에 "상황 · 선택지 · 결정 · 이유 · 포기한 것"을 남기고, docs/decisions.md에 목록을 잇는다. 주인이 면접에서 설명할 수 있어야 한다.
- 서비스를 나눌 때마다 "왜 나눴는가"를 ADR에 남긴다. 나눌 이유가 약하면 합친다.
- 마일스톤이 끝나면 docs/03-development/flow-mN.md에 요청 하나가 지나는 흐름을 mermaid 그림 한 장과 실제 실행 로그로 남긴다.
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
- 경로에 한글이 없다(C:\Users\kiesl\dev\ad-commerce-lab).
- PC 메모리 32GB, Docker 16.6GB·CPU 12. JVM 서비스는 컨테이너마다 메모리 상한(-Xmx)을 둔다.
- 서비스 사이 통신: 비동기는 Kafka 이벤트, 동기는 조회만(호출하는 쪽이 타임아웃·서킷 브레이커·대체 응답 책임). 서비스는 자기 DB만 읽고 쓴다.
