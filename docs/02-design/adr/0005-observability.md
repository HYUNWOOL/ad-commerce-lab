# ADR-0005. 관측: OpenTelemetry 트레이스 + Prometheus 메트릭

- 날짜: 2026-11-21 · 상태: 채택

## 상황
NFR-6: 요청 하나를 서비스 경계(HTTP, Kafka)를 넘어 따라가고, 서킷 상태·컨슈머 지연·오류율을 대시보드로 본다. M2 실험의 전후 숫자도 여기서 읽는다.

## 결정
| 항목 | 선택 | 이유 |
| --- | --- | --- |
| 계측 | Spring Boot 4 `spring-boot-starter-opentelemetry`(Micrometer Observation → OTel 브리지) | 에이전트 없이 Boot 설정만으로 HTTP 서버·RestClient·Kafka가 계측된다 |
| 트레이스 저장 | Jaeger v2 all-in-one(OTLP HTTP 4318, 메모리 저장) | 로컬 실험용으로 컨테이너 하나면 된다 |
| 메트릭 | `/actuator/prometheus`를 Prometheus가 긁어 감. 스타터에 딸린 OTLP 메트릭 레지스트리는 의존성에서 뺀다 | 실험 스크립트가 curl로도 바로 읽을 수 있다. 빼지 않으면 수집기가 없는 곳(테스트)에서 1분마다 전송 실패 로그가 난다 |
| 대시보드 | Grafana, 데이터소스와 대시보드를 파일로 프로비저닝 | `docker compose up`만으로 같은 화면이 뜬다(NFR-7) |
| Kafka | `spring.kafka.template/listener.observation-enabled: true` | 트레이스 문맥이 Kafka 헤더로 건너간다 |
| Outbox | 기록할 때 현재 `traceparent`를 `outbox_events.trace_parent`에 저장하고, 릴레이가 그 문맥 안에서 발행한다 | 릴레이는 스케줄러 스레드에서 돌아 원래 요청의 문맥이 없다. 저장하지 않으면 트레이스가 Outbox에서 끊긴다 |
| 샘플링 | 1.0(전부) | 로컬 실험이라 양이 적다 |
| 잡음 | `@Scheduled` 실행과 `/actuator` 요청 관측은 버린다 | 릴레이는 200ms마다, Prometheus는 5초마다 돌아 빈 트레이스가 쌓인다 |
| 컨슈머 지연 | 브로커 기준(로그 끝 − 커밋 오프셋). Kafka UI와 실험 스크립트(`kafka-consumer-groups.sh`)로 본다 | 클라이언트 `records-lag` 메트릭은 "아직 안 가져온" 메시지만 센다. S4에서 컨슈머가 수백 건을 미리 가져가 느리게 처리하는 동안 이 값은 0이었다(M2 실험에서 발견) |

## 포기한 것
- OpenTelemetry Java 에이전트: 코드 변경 없이 더 많이(JDBC 등) 잡지만, 무엇이 계측되는지 코드에서 보이지 않는다.
- OTel Collector: 서비스가 늘거나 샘플링 정책이 필요해지면 넣는다. 지금은 바로 Jaeger로 보낸다.
- 로그 수집(Loki): 로그에는 Boot 기본 상관 ID(traceId, spanId)만 남기고, 수집은 하지 않는다.
