#!/usr/bin/env bash
# M2 장애 실험(S4~S6). 결과 숫자는 docs/04-verification/m2-resilience.md에 옮긴다.
# 사용: bash scripts/experiments/m2.sh s4 off|fail|on  # PG 지연 2.5초 + 503 50%, 60초
#         off=보호 없음, fail=서킷 + 바로 실패, on=서킷 + 보류 후 재시도(ADR-0006)
#       bash scripts/experiments/m2.sh s5          # 결과를 모르는 결제 → 재조회로 확정
#       bash scripts/experiments/m2.sh s6 on|off   # catalog 지연 3초, campaign 요청 스레드 40개
# 전제: docker compose -f infra/docker-compose.yml up -d 로 전체가 떠 있다.
set -euo pipefail
cd "$(dirname "$0")/../.."
TOXI=http://localhost:8474
PG=http://localhost:18090
GW=http://localhost:8080
KEY=demo-partner-key

sql() { docker exec ad-commerce-lab-postgres-1 psql -U lab -d "$1" -tA -F ' | ' -c "$2"; }
prom() { curl -s --data-urlencode "query=$1" http://localhost:9090/api/v1/query | python -c "import json,sys; r=json.load(sys.stdin)['data']['result']; print(round(float(r[0]['value'][1]),1) if r else 0)"; }
pg_posts() { # PG가 지금까지 받은 승인 요청 수(응답 코드 무관)
  curl -s $PG/actuator/prometheus | python -c "import sys; print(int(sum(float(l.rsplit(' ',1)[1]) for l in sys.stdin if l.startswith('http_server_requests_seconds_count{') and 'method=\"POST\"' in l and 'uri=\"/v1/payments\"' in l)))"
}
chaos() { curl -s -XPUT $PG/admin/chaos -H 'Content-Type: application/json' -d "{\"delayMs\":$1,\"failureRate\":$2}" >/dev/null; }
toxic() { # $1=프록시 $2=이름 $3=종류 $4=방향 $5=속성 JSON
  curl -s -XPOST $TOXI/proxies/$1/toxics -d "{\"name\":\"$2\",\"type\":\"$3\",\"stream\":\"$4\",\"attributes\":$5}" >/dev/null
}
heal() { curl -s -XPOST $TOXI/reset >/dev/null; chaos 0 0; }
wait_healthy() { for port in 8082 8083; do until [ "$(curl -s -o /dev/null -w '%{http_code}' localhost:$port/actuator/health)" = 200 ]; do sleep 1; done; done; }
restore() { docker compose -f infra/docker-compose.yml up -d campaign payment >/dev/null 2>&1; wait_healthy; }

# 보호 켬/끔으로 campaign·payment를 새로 띄운다. 서킷 상태도 새로 시작한다.
mode() { # $1=on|off|fail, 나머지=추가 덮어쓰기 파일
  local files=(-f infra/docker-compose.yml)
  case "$1" in
    off) files+=(-f infra/experiments/no-protection.yml) ;;
    fail) files+=(-f infra/experiments/fail-fast.yml) ;;
  esac
  for f in "${@:2}"; do files+=(-f "$f"); done
  docker compose "${files[@]}" up -d --force-recreate campaign payment >/dev/null 2>&1
  wait_healthy
  sleep 10 # 컨슈머가 파티션을 받을 때까지
}

load() { # $1=주문/초 $2=조회/초 $3=시간 $4=실행 이름
  docker run --rm -i --network ad-commerce-lab_default -e ORDER_RATE="$1" -e READ_RATE="$2" -e DURATION="$3" -e RUN="$4" \
    grafana/k6:2.4.0 run --quiet - < scripts/k6/m2.js 2>/dev/null | grep '^SUMMARY' | sed 's/^/  k6 /' # 요청별 타임아웃 경고(stderr)는 버린다
}

place() { # $1=멱등키 → 주문 ID
  curl -s -X POST $GW/api/orders -H "X-Api-Key: $KEY" -H "Idempotency-Key: $1" -H 'Content-Type: application/json' \
    -d '{"productId":1,"budget":300000,"startDate":"2026-12-01","endDate":"2026-12-07"}' | python -c "import json,sys; print(json.load(sys.stdin)['id'])"
}

# 시작 뒤 들어온 주문이 모두 확정(PAID/CANCELLED)될 때까지 기다리고, 상태별 건수와 확정까지 걸린 시간을 낸다.
settle_report() { # $1=시작 시각(DB 기준)
  local waited=0
  while [ "$(sql campaign "select count(*) from ad_orders where created_at >= '$1' and status = 'PAYMENT_PENDING'")" != 0 ]; do
    sleep 2; waited=$((waited + 2))
    if [ $waited -ge 900 ]; then echo "  15분 안에 확정되지 않음"; break; fi
  done
  echo "  주문 결과 (상태 | 사유 | 건수 | 확정까지 p50초 | p95초 | 최대초):"
  sql campaign "select status, coalesce(failure_reason, '-'), count(*),
      round(percentile_cont(0.5) within group (order by extract(epoch from updated_at - created_at))::numeric, 1),
      round(percentile_cont(0.95) within group (order by extract(epoch from updated_at - created_at))::numeric, 1),
      round(max(extract(epoch from updated_at - created_at))::numeric, 1)
    from ad_orders where created_at >= '$1' group by 1, 2 order by 3 desc" | sed 's/^/    /'
}

# 브로커 기준 컨슈머 지연(로그 끝 - 커밋 오프셋)을 5초마다 재서 최대값을 남긴다.
# 클라이언트 records-lag 메트릭은 "아직 안 가져온" 메시지만 세서, 가져온 뒤 처리 못 한 메시지가 안 보인다(S4 첫 실행에서 0으로 나옴).
lag_watch_start() {
  LAGF=$(mktemp); echo 0 > "$LAGF"
  (
    while :; do
      l=$(MSYS_NO_PATHCONV=1 docker exec ad-commerce-lab-kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:19092 \
        --describe --group payment 2>/dev/null | awk '$2 == "campaign.order" && $6 ~ /^[0-9]+$/ { s += $6 } END { print s + 0 }')
      [ "$l" -gt "$(cat "$LAGF")" ] && echo "$l" > "$LAGF"
      sleep 5
    done
  ) &
  LAGPID=$!
}
lag_watch_stop() { kill "$LAGPID" 2>/dev/null || true; cat "$LAGF"; rm -f "$LAGF"; }

s4() {
  echo "[S4] PG 지연 2.5초 + 503 50%를 60초 동안, 주문 5건/초·조회 20건/초 (서킷 $1)"
  mode "$1"; heal
  local start pg0 t0=$SECONDS
  start=$(sql campaign "select now()"); pg0=$(pg_posts)
  lag_watch_start
  toxic pg slow latency downstream '{"latency":2500}'; chaos 0 0.5
  load 5 20 60s "s4-$1"
  local pg_mid; pg_mid=$(pg_posts)
  heal; echo "  부하가 끝나면서 장애도 해제"
  settle_report "$start"
  local span=$((SECONDS - t0))
  echo "  PG가 받은 승인 요청: 장애 중 $((pg_mid - pg0))건, 복구 뒤 $(($(pg_posts) - pg_mid))건"
  echo "  payment 컨슈머 최대 지연(브로커 기준): $(lag_watch_stop)건"
  echo "  pg 서킷이 막고 있던 시간: 약 $(prom "sum_over_time(max(resilience4j_circuitbreaker_state{service=\"payment\",name=\"pg\",state=~\"open|half_open\"})[${span}s:5s]) * 5")초(열림·반열림)"
}

s5() {
  mode on; heal
  echo "[S5-A] PG가 3.5초 뒤에 처리(읽기 타임아웃 3초): 응답은 못 받지만 PG에서는 승인된다"
  local start pg0
  start=$(sql campaign "select now()"); pg0=$(pg_posts)
  chaos 3500 0
  for _ in $(seq 1 8); do place "s5a-$RANDOM$RANDOM" >/dev/null; done
  settle_report "$start"
  echo "  결제 (상태 | 재조회 횟수 | 건수):"
  sql payment "select status, reconcile_attempts, count(*) from payments where created_at >= '$start' group by 1, 2 order by 1, 2" | sed 's/^/    /'
  echo "  PG가 받은 승인 요청: $(($(pg_posts) - pg0))건 (재조회는 조회로 끝나 재승인 없음)"
  chaos 0 0

  echo "[S5-B] PG로 가는 요청이 20초 동안 닿지 않음(연결은 열리고 데이터는 버려짐) → 복구"
  mode on # A의 승인 타임아웃이 서킷 창에 남아 있으면 B의 새 결제가 바로 실패한다. 서킷을 새로 시작한다.
  start=$(sql campaign "select now()"); pg0=$(pg_posts)
  toxic pg blackhole timeout upstream '{"timeout":0}'
  for _ in $(seq 1 4); do place "s5b-$RANDOM$RANDOM" >/dev/null; done
  sleep 20
  echo "  20초 뒤 결제 상태: $(sql payment "select string_agg(status || ' ' || c, ', ') from (select status, count(*) c from payments where created_at >= '$start' group by 1) t")"
  heal
  local recovered; recovered=$(sql campaign "select now()")
  settle_report "$start"
  echo "  복구 뒤 모두 확정될 때까지: $(sql campaign "select round(max(extract(epoch from updated_at - '$recovered'::timestamptz))::numeric, 1) from ad_orders where created_at >= '$start'")초"
  echo "  PG가 받은 승인 요청: $(($(pg_posts) - pg0))건 (닿지 않은 첫 요청 대신 같은 멱등키로 다시 보낸 것)"
  local found=0 total=0
  for id in $(sql payment "select id from payments where created_at >= '$start'"); do
    total=$((total + 1))
    [ "$(curl -s -o /dev/null -w '%{http_code}' $PG/v1/payments/"$id")" = 200 ] && found=$((found + 1))
  done
  echo "  PG에 기록된 결제: $found / $total (결제 ID = 멱등키라 결제마다 하나)"
}

s6() {
  echo "[S6] catalog 지연 3초, campaign 요청 스레드 40개, 60초 동안 주문 20건/초·조회 20건/초 (보호 $1)"
  mode "$1" infra/experiments/small-threads.yml; heal
  local t0=$SECONDS
  toxic catalog slow latency downstream '{"latency":3000}'
  load 20 20 60s "s6-$1"
  heal
  local span=$((SECONDS - t0))
  echo "  campaign 요청 스레드 최대 사용: $(prom "max_over_time(max(tomcat_threads_busy_threads{service=\"campaign\"})[${span}s:5s])")개 / 40"
  echo "  catalog 서킷이 막고 있던 시간: 약 $(prom "sum_over_time(max(resilience4j_circuitbreaker_state{service=\"campaign\",name=\"catalog\",state=~\"open|half_open\"})[${span}s:5s]) * 5")초(열림·반열림)"
}

case "${1:-}" in
  s4) s4 "${2:?off, fail, on 중 하나}" ;;
  s5) s5 ;;
  s6) s6 "${2:?on 또는 off}" ;;
  *) echo "사용: $0 s4 on|off | s5 | s6 on|off"; exit 1 ;;
esac
heal
restore
