#!/usr/bin/env bash
# (1) 429의 Retry-After 차단이 Web·Batch 양쪽에 적용되는지  (2) Redis가 응답하지 않을 때의 동작
set -uo pipefail
source "$(dirname "$0")/common.sh"
V="$OUT"
total() { mock /__stats | python -c "import sys,json;print(json.load(sys.stdin)['total'])"; }
search() { curl -s -m 25 -L -c "$V/cj" -b "$V/cj" -o "$V/last.html" -w "%{http_code} %{time_total}s" "$WEB_URL/summoner/kr/$1/KR1"; }
msg() { grep -o 'text-red-600 font-medium">[^<]*' "$V/last.html" | sed 's/.*">//'; }

echo "##### 1. Retry-After 차단 공유"
rc del riot:ratelimit:calls riot:ratelimit:blocked >/dev/null
mock /__reset >/dev/null
mock "/__429?count=1&retry=20" >/dev/null
sleep 4      # Batch가 다음 호출에서 429를 받는다
echo "429 직후: 가짜 서버 수신 $(total)건, 차단 키 TTL $(rc pttl riot:ratelimit:blocked)ms"
T1=$(total)
echo "차단 중 Web 검색: $(search block-a)  안내: $(msg)"
sleep 12
echo "차단 중 Web 검색: $(search block-b)  안내: $(msg)"
T2=$(total)
echo "차단 16초 동안 가짜 서버가 받은 요청: $((T2 - T1))건  (Batch는 계속 실행 중, 대기열 $(rc zcard tft:queue:ready)건)"
sleep 9
T3=$(total)
echo "차단 해제 후 5초 내 받은 요청: $((T3 - T2))건, 차단 키 TTL $(rc pttl riot:ratelimit:blocked)"
echo "Batch 로그의 429 기록: $(docker logs tftv_batch 2>&1 | grep 'Rate limit exceeded' | tail -1 | cut -c1-19,60-160)"

echo
echo "##### 2. Redis가 응답하지 않을 때 (docker pause)"
mock /__reset >/dev/null
docker pause tftv_redis >/dev/null
sleep 1
echo "Redis 멈춤 상태에서 Web 검색 1: $(search hang-a)  안내: $(msg)"
echo "Redis 멈춤 상태에서 Web 검색 2: $(search hang-b)  안내: $(msg)"
echo "메인 페이지: $(curl -s -m 15 -o /dev/null -w "%{http_code} %{time_total}s" $WEB_URL/)"
sleep 8
echo "Redis 멈춤 12초 동안 가짜 서버가 받은 요청: $(total)건"
docker unpause tftv_redis >/dev/null
sleep 3
echo "Redis 복구 후 Web 검색: $(search hang-c)  안내: $(msg)"
sleep 4
echo "복구 후 가짜 서버가 받은 요청: $(total)건"
echo "Web 컨테이너의 Redis 타임아웃 설정: $(docker exec tftv_web sh -c 'env | grep SPRING_DATA_REDIS_' | tr '\n' ' ')"
echo "Batch 컨테이너의 Redis 타임아웃 설정: $(docker exec tftv_batch sh -c 'env | grep SPRING_DATA_REDIS_' | tr '\n' ' ')"
echo "Web 로그의 Redis 타임아웃 메시지: $(docker logs tftv_web 2>&1 | grep -o 'Command timed out after [^)]*)\|RedisCommandTimeoutException' | sort | uniq -c | tr '\n' ' ')"
