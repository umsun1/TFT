#!/usr/bin/env bash
# Web + Batch 동시 실행 검증. 실제 Riot이 아니라 가짜 서버(tftv_mock)로만 요청이 간다.
set -uo pipefail
source "$(dirname "$0")/common.sh"
V="$OUT"
SECS="${1:-300}"

# 깨끗한 상태에서 시작
rc del riot:ratelimit:calls riot:ratelimit:blocked tft:queue:ready tft:queue:retries tft:queue:failed >/dev/null
mock "/__config?latencyMs=0&pages=3" >/dev/null
mock /__reset >/dev/null

# Batch 대기열에 소환사 작업 400건(작업당 매치 목록 3회 조회)
for i in $(seq 1 400); do echo "ZADD tft:queue:ready 50 SUMMONER:seed-$i"; done | docker exec -i tftv_redis redis-cli >/dev/null

dc up -d batch > "$V/up_batch.log" 2>&1 || { echo "BATCH UP FAILED"; tail -3 "$V/up_batch.log"; }
for i in $(seq 1 60); do docker logs tftv_batch 2>&1 | grep -q "match fetch scheduler start" && break; sleep 2; done
echo "batch started: $(docker logs tftv_batch 2>&1 | grep -c 'Started BatchApplication')"

# Web 검색 부하: 초당 4명(=Riot 호출 12회/초 요구) + 30초마다 30명 동시 검색
rm -rf "$V/codes"; mkdir -p "$V/codes"
search() { curl -s -m 20 -o /dev/null -w "%{http_code}\n" "$WEB_URL/summoner/kr/load-$1/KR1" > "$V/codes/$1.txt"; }
START=$(date +%s); n=0
while [ $(( $(date +%s) - START )) -lt "$SECS" ]; do
  n=$((n+1)); search "$n" &
  if [ $((n % 120)) -eq 0 ]; then for b in $(seq 1 30); do n=$((n+1)); search "$n" & done; fi
  sleep 0.25
done
wait
sleep 3

echo "=== 가짜 Riot 서버가 받은 요청 (도착 시각 기준)"
mock /__stats
echo "=== 호출 주체 IP"
echo "web=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' tftv_web) batch=$(docker inspect -f '{{range .NetworkSettings.Networks}}{{.IPAddress}}{{end}}' tftv_batch)"
echo "=== Web 검색 결과 (총 $n건)"
cat "$V"/codes/*.txt | sort | uniq -c
echo "=== Redis 호출 기록(최근 2분): $(rc zcard riot:ratelimit:calls)"
echo "=== Batch 로그"
L="$(docker logs tftv_batch 2>&1)"
echo "소환사 작업 완료: $(echo "$L" | grep -c 'Total matches found')  한도 소진으로 미룸: $(echo "$L" | grep -c 'budget exhausted while')  429: $(echo "$L" | grep -c 'Rate limit exceeded')  오류: $(echo "$L" | grep -c 'Error processing queue')"
echo "=== Web 로그: 429 $(docker logs tftv_web 2>&1 | grep -c 'Rate limit exceeded')건, ERROR $(docker logs tftv_web 2>&1 | grep -c ' ERROR ')건"
