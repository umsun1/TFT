# 검증 스크립트 공통 설정. 다른 스크립트가 source 해서 쓴다.
export MSYS_NO_PATHCONV=1                      # Git Bash가 컨테이너 안 경로(/certs 등)를 바꾸지 않게 한다
_here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if command -v cygpath >/dev/null 2>&1; then _here="$(cygpath -m "$_here")"; fi   # Windows(Git Bash)에서는 C:/... 형태로
export VERIFY_DIR="$_here"
ROOT="$(dirname "$(dirname "$VERIFY_DIR")")"
OUT="${VERIFY_OUT:-$VERIFY_DIR/out}"; mkdir -p "$OUT"
WEB_URL="http://localhost:18080"
dc() { (cd "$ROOT" && docker compose -p tftverify -f docker-compose.yml -f "$VERIFY_DIR/verify.override.yml" "$@"); }
# 가짜 Riot 서버의 제어용 주소 호출 (예: mock /__stats)
mock() { docker exec tftv_mock python -c "import urllib.request,ssl;c=ssl._create_unverified_context();print(urllib.request.urlopen('https://localhost$1',context=c).read().decode())"; }
rc() { docker exec tftv_redis redis-cli "$@"; }
