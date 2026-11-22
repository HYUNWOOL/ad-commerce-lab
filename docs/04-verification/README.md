# 4. 검증

가설을 숫자로 확인한 기록. 실험은 모두 스크립트 한 줄로 다시 돌릴 수 있다(NFR-7).

| 문서 | 시나리오 | 다시 돌리는 법 |
| --- | --- | --- |
| [M2 장애 격리](m2-resilience.md) | S4 PG 지연·실패, S5 결과 불명, S6 내부 서비스 지연 | `bash scripts/experiments/m2.sh s4 off\|fail\|on`, `s5`, `s6 off\|on` |
| [M3 노출 과금](m3-billing.md) | S7 처리량, S8 예산 소진, S9 재전송·강제 종료 | `bash scripts/experiments/m3.sh s7 [초당 요청 수]`, `s8`, `s9` |
| [M4 서버 주도 화면](m4-sdui.md) | S10 배포 없이 화면 변경, S11 구버전 앱, S12 화면 일부 출처 장애 | `bash scripts/experiments/m4.sh s10`, `s11`, `s12`, `shots` |
| [M5 AI 에이전트](m5-agent.md) | S13 위임 구매, S14 한도 초과 승인, S15 운영 환불, S16 프롬프트 주입, S17 재시도 | `bash scripts/experiments/m5.sh eval`, `shots` |

## 테스트 층

| 층 | 무엇을 | 어디서 |
| --- | --- | --- |
| 통합 테스트 | 서비스 하나 + 실제 Postgres·Kafka(Testcontainers). 멱등, 상태 전이, 이벤트 발행, 재조회, 과금, 원장 | `./gradlew test` (campaign 10개, payment 13개, delivery 8개, settlement 7개, experience 8개, gateway 5개) |
| 에이전트 | 위임 한도(동시 호출 포함), 멱등, 승인, 환불 결정, 감사 로그 append-only, HTTP 신원 | `cd agent && uv run pytest` (22개, Postgres는 Testcontainers) |
| 화면 렌더러 | 모르는 컴포넌트·그리다 터진 섹션만 대체 표시, 버전 맞추기, 승인 버튼 | `cd web && npm test` (6개) |
| 스모크 | compose로 띄운 전체에 gateway로 요청. 정상 흐름 S1~S3, 주문 → 노출 → 과금 → 정산서 | `bash scripts/e2e-m1.sh`, `bash scripts/e2e-m3.sh` |
| 장애 실험 | Toxiproxy·mock-pg로 장애를 넣고 k6로 부하를 건 채 전후 비교 | `scripts/experiments/` |
| 에이전트 평가 | "모델이 100% 속는다"고 보고 도구 호출 17케이스를 gateway로 보내, campaign DB에서 정책 위반 실행을 센다 | `bash scripts/experiments/m5.sh eval` |

## 실험 환경
Windows 11 노트북(32GB), Docker Desktop(메모리 16GB, CPU 12개)에서 컨테이너 17개(+ 실험 때 k6 하나)를 한 번에 띄운다. 서비스 JVM은 `-Xmx256m`. 절대 숫자보다 같은 환경에서의 전후 차이를 본다.
