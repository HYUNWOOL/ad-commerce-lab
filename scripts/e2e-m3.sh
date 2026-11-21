#!/usr/bin/env bash
# M3 스모크: 주문 → 결제 → 캠페인 ACTIVE → 노출 수집 → 과금 → 원장 → 정산서. compose로 띄운 전체에 확인한다.
# 사용: bash scripts/e2e-m3.sh
set -euo pipefail
BASE=${BASE:-http://localhost:8080}
DELIVERY=${DELIVERY:-http://localhost:8084} # 수집 API는 내부용이라 delivery에 바로 보낸다
KEY=demo-partner-key
H=(-H "X-Api-Key: $KEY")
json() { python -c "import json,sys; d=json.load(sys.stdin); print($1)"; }
until_eq() { # $1=설명 $2=기대값 $3=명령
  local v=""
  for _ in $(seq 1 60); do v=$(eval "$3" 2>/dev/null || true); [ "$v" = "$2" ] && { echo "  통과: $1 = $v"; return 0; }; sleep 0.5; done
  echo "  실패: $1 (마지막 값 $v, 기대 $2)"; return 1
}

echo "[1] 30만 원 주문 → 결제 승인 → 캠페인 ACTIVE"
order=$(curl -s -X POST "$BASE/api/orders" "${H[@]}" -H "Idempotency-Key: m3-$RANDOM$RANDOM" -H 'Content-Type: application/json' \
  -d '{"productId":1,"budget":300000,"startDate":"2026-12-01","endDate":"2026-12-07"}' | json "d['id']")
until_eq "주문 상태" PAID "curl -s $BASE/api/orders/$order -H 'X-Api-Key: $KEY' | python -c \"import json,sys; print(json.load(sys.stdin)['status'])\""
campaign=$(curl -s "$BASE/api/campaigns" "${H[@]}" | json "[c['id'] for c in d if c['orderId']=='$order'][0]")
echo "  캠페인 $campaign"
stats() { curl -s "$BASE/api/campaigns/$campaign/stats" "${H[@]}" | python -c "import json,sys; d=json.load(sys.stdin); print(d.get('$1'))"; }
until_eq "delivery 예산" 300000 "stats remaining"

echo "[2] 노출 100건 + 같은 노출 100건 재전송 → 100건만 과금(단가 50원)"
body=$(python -c "
import json,uuid,datetime
now=datetime.datetime.now(datetime.timezone.utc).isoformat().replace('+00:00','Z')
print(json.dumps([{'eventId':str(uuid.uuid4()),'campaignId':'$campaign','placement':'search-top','occurredAt':now} for _ in range(100)]))")
for _ in 1 2; do
  code=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$DELIVERY/impressions" -H 'Content-Type: application/json' -d "$body")
  [ "$code" = 202 ] || { echo "  실패: 수집 응답 $code"; exit 1; }
done
until_eq "delivery 사용액" 5000 "stats spent"
until_eq "campaign 사용액(복제)" 5000 "curl -s $BASE/api/campaigns -H 'X-Api-Key: $KEY' | python -c \"import json,sys; print([c['spent'] for c in json.load(sys.stdin) if c['id']=='$campaign'][0])\""

echo "[3] 정산서: 이번 달 결제액·사용액·잔액(파트너 P-DEMO 누적)"
month=$(TZ=Asia/Seoul date +%Y-%m)
curl -s "$BASE/api/settlements?month=$month" "${H[@]}" | python -c "import json,sys; d=json.load(sys.stdin); print('  ', d)"
echo "[4] 원장 검산: 전체 차변 합 = 대변 합, 파트너 사용액 = delivery 사용액 합"
docker exec ad-commerce-lab-postgres-1 psql -U lab -d settlement -tA -c \
  "select '  차변 ' || sum(debit) || ' / 대변 ' || sum(credit) || case when sum(debit) = sum(credit) then ' 통과' else ' 실패' end from journal_lines"
ledger_used=$(docker exec ad-commerce-lab-postgres-1 psql -U lab -d settlement -tA -c "select coalesce(sum(debit),0) from journal_lines where account = 'PARTNER_PREPAID'")
delivery_spent=$(docker exec ad-commerce-lab-postgres-1 psql -U lab -d delivery -tA -c "select coalesce(sum(spent),0) from campaign_budgets")
[ "$ledger_used" = "$delivery_spent" ] && echo "  통과: 원장 사용액 $ledger_used = delivery 사용액 $delivery_spent" || { echo "  실패: 원장 $ledger_used ≠ delivery $delivery_spent"; exit 1; }
echo "M3 스모크 완료"
