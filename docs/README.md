# 문서 지도

이 프로젝트는 기획 → 설계 → 개발 → 검증 순서로 문서를 남긴다. 앞 단계 문서의 번호(시나리오 S, 요구사항 FR·NFR)를 뒤 단계에서 그대로 참조한다.

| 단계 | 폴더 | 내용 | 상태 |
| --- | --- | --- | --- |
| 1. 기획 | [01-planning](01-planning) | [문제 정의](01-planning/01-problem.md) · [사용자와 시나리오](01-planning/02-users-scenarios.md) · [요구사항](01-planning/03-requirements.md) · [범위와 로드맵](01-planning/04-scope-roadmap.md) | 완료 |
| 2. 설계 | [02-design](02-design) | [아키텍처](02-design/01-architecture.md) · [도메인 모델·상태 머신](02-design/02-domain-model.md) · [이벤트 명세](02-design/03-events.md) · [API 명세](02-design/04-api.md) · [ADR](02-design/adr) | 완료(M1 범위 상세, 이후 단계는 진행하며 보강) |
| 3. 개발 | [03-development](03-development) | [M1 작업 계획](03-development/m1-plan.md), [M2 작업 계획](03-development/m2-plan.md), [M3 작업 계획](03-development/m3-plan.md), 마일스톤별 흐름 기록(GitHub Milestone·Issue와 연결) | 진행 중 |
| 4. 검증 | [04-verification](04-verification) | 테스트 전략, 장애·부하 실험 결과([M2 장애 격리](04-verification/m2-resilience.md), [M3 노출 과금](04-verification/m3-billing.md)), 가설 판정, 회고 | 진행 중 |

결정 기록: [decisions.md](decisions.md)
