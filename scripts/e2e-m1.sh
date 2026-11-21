#!/usr/bin/env bash
# M1 스모크: compose로 띄운 전체 시스템에 gateway로 요청해 S1~S3를 확인한다.
# 사용: bash scripts/e2e-m1.sh
set -euo pipefail
BASE=${BASE:-http://localhost:8080}
KEY=demo-partner-key
BODY=$(mktemp)
json() { python -c "import json,sys; print(json.load(sys.stdin)$1)"; }
post_order() { # $1=멱등키 $2=예산
  curl -s -o "$BODY" -w '%{http_code}' -X POST "$BASE/api/orders" -H "X-Api-Key: $KEY" \
    -H "Idempotency-Key: $1" -H 'Content-Type: application/json' \
    -d "{\"productId\":1,\"budget\":$2,\"startDate\":\"2026-12-01\",\"endDate\":\"2026-12-07\"}"
}
wait_status() { # $1=주문ID $2=기대 상태
  local s=""
  for _ in $(seq 1 40); do
    s=$(curl -s "$BASE/api/orders/$1" -H "X-Api-Key: $KEY" | json "['status']")
    [ "$s" = "$2" ] && return 0
    sleep 0.5
  done
  echo "  실패: 20초 안에 $2 가 되지 않음(마지막 상태: $s)"; return 1
}
pass() { echo "  통과: $1"; }

echo "[0] 키 없이 요청하면 401"
[ "$(curl -s -o /dev/null -w '%{http_code}' "$BASE/api/catalog/products")" = 401 ] && pass "401"

echo "[S1] 30만 원 주문 → 결제 승인 → 캠페인 ACTIVE"
code=$(post_order "s1-$RANDOM-$RANDOM" 300000); id=$(json "['id']" < "$BODY")
echo "  주문 $id 응답 $code"
wait_status "$id" PAID && pass "주문 PAID"
curl -s "$BASE/api/payments?orderId=$id" -H "X-Api-Key: $KEY" | json "['status']" | grep -q APPROVED && pass "결제 APPROVED"
curl -s "$BASE/api/campaigns" -H "X-Api-Key: $KEY" | python -c "import json,sys; c=[x for x in json.load(sys.stdin) if x['orderId']=='$id']; assert c and c[0]['status']=='ACTIVE'" && pass "캠페인 ACTIVE"

echo "[S2] 같은 멱등키로 10번 → 주문 1건"
k="s2-$RANDOM-$RANDOM"; ids=""; codes=""
for _ in $(seq 1 10); do codes="$codes $(post_order "$k" 300000)"; ids="$ids $(json "['id']" < "$BODY")"; done
echo "  응답 코드:$codes"
[ "$(echo $ids | tr ' ' '\n' | sort -u | wc -l)" = 1 ] && pass "주문 ID 1개"

echo "[S3] 600만 원(카드 한도 초과) → 결제 거절 → 주문 CANCELLED"
code=$(post_order "s3-$RANDOM-$RANDOM" 6000000); id=$(json "['id']" < "$BODY")
wait_status "$id" CANCELLED && pass "주문 CANCELLED"
curl -s "$BASE/api/orders/$id" -H "X-Api-Key: $KEY" | json "['failureReason']" | grep -q DECLINED && pass "사유 DECLINED"

rm -f "$BODY"
echo "M1 스모크 완료"
