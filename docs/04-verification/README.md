# 4. 검증

가설을 숫자로 확인한 기록. 실험은 모두 스크립트 한 줄로 다시 돌릴 수 있다(NFR-7).

| 문서 | 시나리오 | 다시 돌리는 법 |
| --- | --- | --- |
| [M2 장애 격리](m2-resilience.md) | S4 PG 지연·실패, S5 결과 불명, S6 내부 서비스 지연 | `bash scripts/experiments/m2.sh s4 off\|fail\|on`, `s5`, `s6 off\|on` |
| [M3 노출 과금](m3-billing.md) | S7 처리량, S8 예산 소진, S9 재전송·강제 종료 | `bash scripts/experiments/m3.sh s7 [초당 요청 수]`, `s8`, `s9` |

## 테스트 층

| 층 | 무엇을 | 어디서 |
| --- | --- | --- |
| 통합 테스트 | 서비스 하나 + 실제 Postgres·Kafka(Testcontainers). 멱등, 상태 전이, 이벤트 발행, 재조회, 과금, 원장 | `./gradlew test` (campaign 9개, payment 11개, delivery 7개, settlement 6개) |
| 스모크 | compose로 띄운 전체에 gateway로 요청. 정상 흐름 S1~S3, 주문 → 노출 → 과금 → 정산서 | `bash scripts/e2e-m1.sh`, `bash scripts/e2e-m3.sh` |
| 장애 실험 | Toxiproxy·mock-pg로 장애를 넣고 k6로 부하를 건 채 전후 비교 | `scripts/experiments/` |

## 실험 환경
Windows 11 노트북(32GB), Docker Desktop(메모리 16GB, CPU 12개)에서 컨테이너 14개(+ 실험 때 k6 하나)를 한 번에 띄운다. 서비스 JVM은 `-Xmx256m`. 절대 숫자보다 같은 환경에서의 전후 차이를 본다.
