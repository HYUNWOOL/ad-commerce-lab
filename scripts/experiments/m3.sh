#!/usr/bin/env bash
# M3 노출 과금 실험(S7~S9). 결과 숫자는 docs/04-verification/m3-billing.md에 옮긴다.
# 사용: bash scripts/experiments/m3.sh s7 [초당 요청 수=50]   # 요청 하나에 노출 100건, 60초
#       bash scripts/experiments/m3.sh s8                     # 예산 10만 원 캠페인에 노출 30%를 몰아줌
#       bash scripts/experiments/m3.sh s9                     # 재전송 20% + 20초에 delivery를 강제 종료
# 전제: docker compose -f infra/docker-compose.yml up -d 로 전체가 떠 있다.
set -euo pipefail
cd "$(dirname "$0")/../.."
export MSYS_NO_PATHCONV=1 # Git Bash가 /opt/... 같은 인자를 Windows 경로로 바꾸지 않게
GW=http://localhost:8080
KEY=demo-partner-key

sql() { docker exec ad-commerce-lab-postgres-1 psql -U lab -d "$1" -tA -F ' | ' -c "$2"; }
prom() { curl -s --data-urlencode "query=$1" http://localhost:9090/api/v1/query | python -c "import json,sys; r=json.load(sys.stdin)['data']['result']; print(round(float(r[0]['value'][1]),3) if r else 'n/a')"; }
field() { python -c "import json,sys; d=json.load(sys.stdin); print($1)"; }

create_campaign() { # $1=예산 → 캠페인 ID(상품 1, 단가 50원). 주문 → 결제 → ACTIVE → delivery 예산까지 기다린다.
  local order campaign
  order=$(curl -s -X POST "$GW/api/orders" -H "X-Api-Key: $KEY" -H "Idempotency-Key: m3x-$RANDOM$RANDOM" -H 'Content-Type: application/json' \
    -d "{\"productId\":1,\"budget\":$1,\"startDate\":\"2026-12-01\",\"endDate\":\"2026-12-07\"}" | field "d['id']")
  for _ in $(seq 1 60); do
    campaign=$(curl -s "$GW/api/campaigns" -H "X-Api-Key: $KEY" | field "next((c['id'] for c in d if c['orderId']=='$order'), '')")
    [ -n "$campaign" ] && [ "$(curl -s -o /dev/null -w '%{http_code}' "$GW/api/campaigns/$campaign/stats" -H "X-Api-Key: $KEY")" = 200 ] && { echo "$campaign"; return; }
    sleep 0.5
  done
  echo "캠페인을 만들지 못함(주문 $order)" >&2; exit 1
}

broker_lag() { # 브로커 기준 delivery-billing 지연(로그 끝 - 커밋 오프셋). 부를 때마다 JVM이 떠서 무거우니 자주 부르지 않는다
  docker exec ad-commerce-lab-kafka-1 /opt/kafka/bin/kafka-consumer-groups.sh --bootstrap-server localhost:19092 \
    --describe --group delivery-billing 2>/dev/null | awk '$2 == "delivery.impression" && $6 ~ /^[0-9]+$/ { s += $6 } END { print s + 0 }'
}

load() { # 환경 변수를 그대로 k6에 넘긴다
  docker run --rm -i --network ad-commerce-lab_default "$@" grafana/k6:2.4.0 run --quiet - < scripts/k6/m3.js 2>/dev/null | grep '^SUMMARY' | sed 's/^SUMMARY //'
}

# 실험 하나: 캠페인을 만들고, 부하를 걸고, 밀린 것이 다 빠질 때까지 기다린 뒤 정확도를 검산한다.
run() { # $1=이름 $2=초당 요청 $3=재전송 비율 $4=핫 캠페인 비율(0이면 없음) $5=강제 종료 초(0이면 안 함)
  local name=$1 rate=$2 dup=$3 hot_share=$4 kill_at=$5 campaigns=() hot="" i
  echo "[$name] 초당 요청 $rate × 노출 100건, 60초, 재전송 $dup, 핫 캠페인 비율 $hot_share, 강제 종료 ${kill_at}초"
  for i in $(seq 1 10); do campaigns+=("$(create_campaign 3000000)"); done
  [ "$hot_share" != 0 ] && hot=$(create_campaign 100000)
  local ids; ids=$(IFS=,; echo "${campaigns[*]}")
  local all; all=$(printf "'%s'," "${campaigns[@]}" $hot); all=${all%,} # SQL IN 목록
  echo "  캠페인 10개(예산 300만 원)${hot:+ + 핫 캠페인 1개(예산 10만 원 = 노출 2,000건)} 준비"

  local t0=$SECONDS out killer="" start
  start=$(sql delivery "SELECT now()")
  if [ "$kill_at" != 0 ]; then
    # docker kill은 "직접 멈춤"으로 쳐서 재시작 정책이 다시 띄우지 않는다. 바로 다시 띄운다(프로세스가 죽었다 살아난 상황).
    ( sleep "$kill_at"; docker kill --signal KILL ad-commerce-lab-delivery-1 >/dev/null; docker start ad-commerce-lab-delivery-1 >/dev/null
      echo "  ${kill_at}초: delivery 강제 종료(SIGKILL) 후 다시 시작" ) &
    killer=$!
  fi
  out=$(load -e CAMPAIGNS="$ids" -e HOT="$hot" -e HOT_SHARE="$hot_share" -e RATE="$rate" -e DUP="$dup")
  [ -n "$killer" ] && wait "$killer"
  local sent_at=$SECONDS
  until [ "$(broker_lag)" = 0 ]; do sleep 5; done
  local drained=$((SECONDS - sent_at))
  echo "  k6: $out"
  echo "  수집 처리량: $(echo "$out" | field "round((d['uniqueEvents'] + d['dupEvents']) / d['seconds'])")건/초 (재전송 포함), 수집 API p95 $(echo "$out" | field "d['p95ms']")ms"
  echo "  부하가 끝난 뒤 과금 컨슈머가 다 따라잡을 때까지: 5초 단위로 ${drained}초"
  local span=$((SECONDS - t0))
  echo "  과금 지연(노출 발생 → 과금 트랜잭션) p50 / p95 / p99: $(for q in 0.5 0.95 0.99; do prom "histogram_quantile($q, sum by (le) (increase(delivery_billing_lag_seconds_bucket[${span}s])))"; done | paste -sd/ | sed 's|/| / |g')초"

  echo "  검산(캠페인별 과금한 노출 = min(처음 본 노출, 예산 ÷ 단가), 과금 기록 합 = 사용액):"
  sql delivery "
    WITH r AS (SELECT campaign_id, count(*) AS recorded FROM impression_events WHERE campaign_id IN ($all) GROUP BY 1),
         c AS (SELECT campaign_id, sum(impressions) AS charged_rows, sum(amount) AS amount FROM charges WHERE campaign_id IN ($all) GROUP BY 1)
    SELECT '    캠페인 ' || count(*) || '개, 처음 본 노출 ' || sum(r.recorded) || ', 과금한 노출 ' || sum(b.spent / b.cpv_price)
           || ', 기대와 다른 캠페인 ' || count(*) FILTER (WHERE b.spent / b.cpv_price <> LEAST(r.recorded, b.budget / b.cpv_price))
           || ', 과금 기록과 사용액이 다른 캠페인 ' || count(*) FILTER (WHERE c.amount IS DISTINCT FROM b.spent)
           || ', 예산 초과 ' || count(*) FILTER (WHERE b.spent > b.budget)
    FROM campaign_budgets b JOIN r USING (campaign_id) LEFT JOIN c USING (campaign_id)"
  echo "    k6가 보낸 처음 노출 $(echo "$out" | field "d['uniqueEvents']")건(포기 $(echo "$out" | field "d['abandonedEvents']")건)"
  if [ -n "$hot" ]; then
    echo "  핫 캠페인: $(sql delivery "SELECT '사용액 ' || spent || ' / 예산 ' || budget || ', 상태 ' || status || ', BudgetExhausted ' || (SELECT count(*) FROM outbox_events WHERE message_key = '$hot' AND payload LIKE '%BudgetExhausted%') || '건' FROM campaign_budgets WHERE campaign_id = '$hot'"), 받은 노출 $(echo "$out" | field "d['hotUniqueEvents']")건"
    echo "  campaign 서비스의 핫 캠페인 상태: $(curl -s "$GW/api/campaigns" -H "X-Api-Key: $KEY" | field "[c['status'] + ' ' + str(c['spent']) for c in d if c['id']=='$hot'][0]")"
  fi
  echo "  과금 이벤트(CampaignCharged): $(sql delivery "SELECT count(*) || '건, 한 건에 노출 평균 ' || round(avg(impressions), 1) || '건' FROM charges WHERE created_at >= '$start'")"
  local delivery_total ledger_total=""
  delivery_total=$(sql delivery "SELECT sum(spent) FROM campaign_budgets")
  for _ in $(seq 1 900); do
    ledger_total=$(sql settlement "SELECT coalesce(sum(debit), 0) FROM journal_lines WHERE account = 'PARTNER_PREPAID'")
    [ "$ledger_total" = "$delivery_total" ] && break; sleep 1
  done
  echo "  부하가 끝난 뒤 원장이 delivery 사용액을 따라잡을 때까지: $((SECONDS - sent_at))초 (Outbox 발행: $(sql delivery "SELECT round(extract(epoch from max(published_at) - min(created_at)))::int || '초 동안 ' || count(*) || '건' FROM outbox_events WHERE created_at >= '$start'"))"
  echo "  원장: 사용액 $ledger_total / delivery 사용액 $delivery_total $([ "$ledger_total" = "$delivery_total" ] && echo 일치 || echo 불일치), 차변=대변 $(sql settlement "SELECT sum(debit) = sum(credit) FROM journal_lines")"
}

case "${1:-}" in
  s7) run S7 "${2:-50}" 0 0 0 ;;
  s8) run S8 50 0 0.3 0 ;;
  s9) run S9 50 0.2 0 20 ;;
  *) echo "사용: $0 s7 [초당 요청 수] | s8 | s9"; exit 1 ;;
esac
