#!/usr/bin/env bash
# M4 서버 주도 화면 실험(S10~S12). 결과는 docs/04-verification/m4-sdui.md에 옮긴다.
# 사용: bash scripts/experiments/m4.sh s10   # 운영자가 문구·순서를 바꾸면 다음 요청부터 반영되는가
#       bash scripts/experiments/m4.sh s11   # 구버전 앱이 모르는 컴포넌트를 받으면
#       bash scripts/experiments/m4.sh s12   # 성과 출처(delivery)가 느리거나 죽으면
#       bash scripts/experiments/m4.sh shots # 화면 캡처(docs/04-verification/img)
# 전제: docker compose -f infra/docker-compose.yml up -d 로 전체가 떠 있다(web 포함).
set -euo pipefail
cd "$(dirname "$0")/../.."
export MSYS_NO_PATHCONV=1 PYTHONIOENCODING=utf-8
source scripts/experiments/lib.sh
GW=http://localhost:8080
PARTNER=demo-partner-key
OPERATOR=demo-operator-key # 로컬 데모 운영자 키(gateway 설정, 실제 비밀값 아님)
NEW="Banner@1,SettlementSummary@1,BudgetGauge@1,CampaignPerformanceList@2,ActionButton@1,ProductList@1,PurchaseForm@1,Fallback@1"
OLD="Banner@1,SettlementSummary@1,CampaignPerformanceList@1,ActionButton@1,ProductList@1,PurchaseForm@1,Fallback@1"

screen() { curl -s "$GW/api/screens/$1" -H "X-Api-Key: $PARTNER" -H "X-Client-Components: ${2:-$NEW}"; }
summary() { python -c "
import json,sys; d=json.load(sys.stdin)
print('    리비전', d['revision'])
for s in d['sections']:
    p=s['props']; extra=p.get('reason') or p.get('text') or ''
    print(f\"    {s['id']:<12} {s['type']:<24} v{s['version']}  {extra}\")"; }
code() { curl -s -o /dev/null -w '%{http_code}' "$@"; }
# 한글이 든 JSON은 명령줄 인자로 넘기면 Git Bash가 인코딩을 바꿔 400이 난다. 본문은 표준 입력으로 보낸다.
put() { printf '%s' "$3" | curl -s ${4:+-o /dev/null -w '%{http_code}'} -X PUT "$GW/api/admin/screens/$1" -H "X-Api-Key: $2" -H 'Content-Type: application/json' --data-binary @-; }

s10() {
  echo "[S10] 운영자가 광고 상품 화면의 환불 안내 문구를 바꾸고 맨 아래로 옮긴다"
  echo "  바꾸기 전:"; screen products | summary
  local config original changed started
  config=$(curl -s "$GW/api/admin/screens/products" -H "X-Api-Key: $OPERATOR")
  original=$(echo "$config" | python -c "import json,sys; d=json.load(sys.stdin); print(json.dumps({'baseRevision': d['revision'], 'layout': d['layout']}, ensure_ascii=False))")
  changed=$(echo "$config" | python -c "
import json,sys; d=json.load(sys.stdin); s=d['layout']['sections']
refund=[x for x in s if x['id']=='refund'][0]; refund['props']['text']='12월부터 집행 중 환불은 쓴 금액과 수수료 5%를 뺀 나머지를 돌려드립니다.'
d['layout']['sections']=[x for x in s if x['id']!='refund']+[refund]
print(json.dumps({'baseRevision': d['revision'], 'layout': d['layout']}, ensure_ascii=False))")
  started=$(date +%s%3N)
  echo "  저장: $(put products "$OPERATOR" "$changed")"
  echo "  바로 다음 요청($(( $(date +%s%3N) - started ))ms 뒤):"; screen products | summary
  echo "  구버전 앱도 같은 문구를 받는다:"; screen products "$OLD" | python -c "import json,sys; print('   ', [s['props'].get('text') for s in json.load(sys.stdin)['sections'] if s['id']=='refund'][0])"
  echo "  권한·검사:"
  echo "    파트너 키로 설정 변경 → $(put products "$PARTNER" "$changed" code)"
  echo "    낡은 리비전으로 저장 → $(put products "$OPERATOR" "$changed" code)"
  local latest; latest=$(curl -s "$GW/api/admin/screens/products" -H "X-Api-Key: $OPERATOR" | python -c "import json,sys; print(json.load(sys.stdin)['revision'])")
  echo "    모르는 컴포넌트로 저장 → $(code -X PUT "$GW/api/admin/screens/products" -H "X-Api-Key: $OPERATOR" -H 'Content-Type: application/json' \
    -d "{\"baseRevision\":$latest,\"layout\":{\"title\":\"광고 상품\",\"sections\":[{\"id\":\"x\",\"type\":\"Carousel\",\"version\":1}]}}")"
  echo "  되돌리기(옛 layout을 새 리비전으로 저장): $(echo "$original" | python -c "
import json,sys; d=json.load(sys.stdin); d['baseRevision']=$latest; print(json.dumps(d, ensure_ascii=False))" \
    | curl -s -X PUT "$GW/api/admin/screens/products" -H "X-Api-Key: $OPERATOR" -H 'Content-Type: application/json' -d @-)"
}

s11() {
  echo "[S11] 같은 홈 화면을 최신 앱과 구버전 앱(BudgetGauge를 모르고 성과 목록 v1까지만 앎)이 받는다"
  echo "  최신 앱:"; screen home "$NEW" | summary
  echo "  구버전 앱:"; screen home "$OLD" | summary
}

LAST=$(cygpath -w "$(mktemp)") # 경로 변환을 꺼 두었으므로 Windows 프로그램(curl, python)에 줄 경로는 직접 바꾼다
timing() { # $1=횟수 → 화면 응답 시간 p50/p95/최대(ms)와 마지막 응답의 섹션 상태
  local n=$1 i t times=()
  for i in $(seq 1 "$n"); do
    t=$(curl -s -o "$LAST" -w '%{time_total}' "$GW/api/screens/home" -H "X-Api-Key: $PARTNER" -H "X-Client-Components: $NEW")
    times+=("$t")
  done
  printf '%s\n' "${times[@]}" | python -c "
import sys; xs=sorted(float(x)*1000 for x in sys.stdin); n=len(xs)
print(f'    {n}회: p50 {xs[n//2]:.0f}ms, p95 {xs[min(n-1,int(n*0.95))]:.0f}ms, 최대 {xs[-1]:.0f}ms')"
  python -c "import json,sys; d=json.load(open(sys.argv[1],encoding='utf-8')); print('    마지막 응답:', ', '.join(s['id']+('('+s['props']['reason']+')' if s['type']=='Fallback' else '') for s in d['sections']))" "$LAST"
}
breaker() { curl -s localhost:8086/actuator/circuitbreakers | python -c "import json,sys; print('    서킷:', {k:v['state'] for k,v in json.load(sys.stdin)['circuitBreakers'].items()})"; }

s12() {
  echo "[S12] 성과 출처(delivery)가 느리거나 죽으면 홈 화면은?"
  curl -s -XPOST localhost:8474/reset >/dev/null
  echo "  평소:"; timing 20
  echo "  delivery 응답 3초 지연(Toxiproxy):"
  curl -s -XPOST localhost:8474/proxies/delivery/toxics -d '{"name":"slow","type":"latency","stream":"downstream","attributes":{"latency":3000}}' >/dev/null
  timing 30; breaker
  curl -s -XPOST localhost:8474/reset >/dev/null
  echo "  delivery 컨테이너 중지:"
  docker stop ad-commerce-lab-delivery-1 >/dev/null
  sleep 12 # 열린 서킷이 반열림으로 넘어갈 시간
  timing 20; breaker
  docker start ad-commerce-lab-delivery-1 >/dev/null
  until [ "$(code localhost:8084/actuator/health)" = 200 ]; do sleep 2; done
  sleep 12
  echo "  delivery 복구 뒤:"; timing 20; breaker
}

shots() {
  echo "[캡처] docs/04-verification/img"
  curl -s -XPOST localhost:8474/reset >/dev/null
  shot home-new "screen=home"
  shot home-old "screen=home&client=old"
  shot products "screen=products" 1000
  docker stop ad-commerce-lab-delivery-1 >/dev/null; sleep 12
  shot home-delivery-down "screen=home"
  docker start ad-commerce-lab-delivery-1 >/dev/null
}

case "${1:-}" in
  s10) s10 ;; s11) s11 ;; s12) s12 ;; shots) shots ;;
  *) echo "사용: $0 s10 | s11 | s12 | shots"; exit 1 ;;
esac
