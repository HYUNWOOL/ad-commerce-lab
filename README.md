# agent-checkout

AI 쇼핑 에이전트가 대신 주문·결제해도, 금액과 상태는 틀리지 않는 커머스 결제 플랫폼입니다.

> 판단은 에이전트가, 금액과 상태는 코드가 결정한다.

- 기획: [docs/PLAN.md](docs/PLAN.md)
- 설계 결정 기록: [docs/decisions.md](docs/decisions.md)

## 상태

| 단계 | 내용 | 상태 |
| --- | --- | --- |
| M1 결제 코어 | 멱등키, 상태 머신, Transactional Outbox → Kafka, Saga 보상 | 진행 전 |
| M2 장애 격리·관측 | 서킷 브레이커, 장애 주입, OpenTelemetry, 부하 측정 | 진행 전 |
| M3 원장·정산·대사 | 복식부기 원장, Inbox 멱등 소비, 정산, 대사 | 진행 전 |
| M4 에이전트·SDUI | MCP 결제 도구, 위임 한도·사람 승인, Server-Driven UI | 진행 전 |

## 기술

Kotlin · Spring Boot · PostgreSQL · Kafka · Resilience4j · OpenTelemetry · Python · FastAPI · MCP · React · Docker Compose
