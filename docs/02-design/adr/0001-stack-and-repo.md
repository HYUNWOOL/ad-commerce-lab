# ADR-0001. 기술 스택과 저장소 구조

- 날짜: 2026-11-21 · 상태: 채택

## 상황
업무 서비스 8개를 Kotlin으로 만든다(D2). 버전 조합이 맞지 않으면 시간을 크게 잃는다.

## 결정
| 항목 | 선택 | 근거 |
| --- | --- | --- |
| Spring Boot | 4.1.3 | 4.2.0은 갓 나온 버전이고 start.spring.io의 Spring Cloud 호환표가 아직 RC만 연결한다. 한 단계 아래 최신 패치를 쓴다 |
| Spring Cloud | 2025.1.5 | Boot 4.0~4.1 호환(start.spring.io bom-ranges) |
| Kotlin | 2.4.21 | 최신 안정판(2.5는 RC) |
| JDK | 25 | 설치된 LTS |
| Resilience4j | 2.4.0 (resilience4j-spring-boot4) | Boot 4 전용 아티팩트 |
| Gradle | 9.9.2 (wrapper) | 최신 |
| DB 접근 | Spring JDBC `JdbcClient` + Flyway | SQL을 직접 보고 쓴다. 상태 전이·잠금을 숨기지 않는다 |
| 테스트 | JUnit 5 + Testcontainers(Postgres, Kafka) | 실제 인프라에서 Outbox·Inbox·잠금을 검증 |
| 저장소 | 하나의 저장소, Gradle 멀티 모듈 + 공통 모듈(common) | 공통 규칙을 한 번만 만들고 함께 테스트 |

## 포기한 것
- JPA: 엔티티 매핑이 편하지만, 이 프로젝트는 잠금·상태 전이 SQL을 드러내 보이는 것이 목적이다.
- 서비스별 저장소: 실제 MSA 조직에 더 가깝지만, 혼자 6주 안에 하기에는 버전·공통 코드 관리 비용이 크다.
