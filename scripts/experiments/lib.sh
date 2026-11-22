# 실험 스크립트 공통 함수. m4.sh, m5.sh가 source 한다.

shot() { # $1=파일 이름 $2=쿼리
  local edge="/c/Program Files (x86)/Microsoft/Edge/Application/msedge.exe" profile out
  profile=$(mktemp -d); out="docs/04-verification/img/$1.png"; mkdir -p docs/04-verification/img
  # 사용 중인 Edge와 섞이지 않게 임시 프로필로 띄운다
  "$edge" --headless=new --disable-gpu --hide-scrollbars --no-first-run --user-data-dir="$(cygpath -w "$profile")" \
    --window-size=470,"${3:-1050}" --virtual-time-budget=5000 --screenshot="$(cygpath -w "$PWD/$out")" "http://localhost:5173/?$2" >/dev/null 2>&1 || true
  # Edge 실행 파일은 먼저 끝나고 브라우저는 뒤에서 캡처를 마친다. 파일이 생길 때까지 기다린 뒤 임시 프로필을 지운다.
  for _ in $(seq 1 30); do [ -s "$out" ] && break; sleep 1; done
  sleep 2; rm -rf "$profile" 2>/dev/null || true
  [ -s "$out" ] && echo "  $out" || echo "  캡처 실패: $out"
}
