#!/usr/bin/env bash
# M5 에이전트 평가(S13~S17)와 승인 화면 캡처. 결과는 docs/04-verification/m5-agent.md에 옮긴다.
# 사용: bash scripts/experiments/m5.sh eval   # 모델이 주입에 100% 속는다고 보고 도구 호출을 보내, 정책 위반 실행을 센다
#       bash scripts/experiments/m5.sh shots  # 승인 요청 화면(최신 앱·구버전 앱) 캡처. eval 뒤에 돌리면 대기 요청이 보인다
# 전제: docker compose -f infra/docker-compose.yml up -d 로 전체가 떠 있고, agent/.venv가 있다(cd agent && uv sync).
set -euo pipefail
cd "$(dirname "$0")/../.."
export MSYS_NO_PATHCONV=1 PYTHONIOENCODING=utf-8
source scripts/experiments/lib.sh
PY=agent/.venv/Scripts/python; [ -x "$PY" ] || PY=agent/.venv/bin/python

case "${1:-}" in
  eval) "$PY" scripts/experiments/m5_eval.py ;;
  shots) echo "[캡처] docs/04-verification/img"; shot approvals-new "screen=approvals" 1400; shot approvals-old "screen=approvals&client=old" 600 ;;
  *) echo "사용: $0 eval | shots"; exit 1 ;;
esac
