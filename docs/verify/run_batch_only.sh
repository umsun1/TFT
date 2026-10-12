#!/usr/bin/env bash
# Batch만 돌려 1초 한도 설정에 따른 수집 처리량을 비교한다. usage: run_batch_only.sh <perSecond> <latencyMs> <seconds>
set -uo pipefail
source "$(dirname "$0")/common.sh"
V="$OUT"
PER_SEC="$1"; LAT="$2"; SECS="$3"

dc stop batch >/dev/null 2>&1
dc rm -f batch >/dev/null 2>&1
rc del riot:ratelimit:calls riot:ratelimit:blocked tft:queue:ready tft:queue:retries tft:queue:failed >/dev/null
# 소환사 1명당 매치 목록 12쪽(=연속 12회 호출): 1초 한도에 가장 불리한 작업
mock "/__config?latencyMs=$LAT&pages=12" >/dev/null
for i in $(seq 1 300); do echo "ZADD tft:queue:ready 50 SUMMONER:b$PER_SEC-$LAT-$i"; done | docker exec -i tftv_redis redis-cli >/dev/null
mock /__reset >/dev/null

BATCH_PER_SECOND="$PER_SEC" dc up -d batch > "$V/up_batch.log" 2>&1 || { echo "BATCH UP FAILED"; tail -3 "$V/up_batch.log"; exit 1; }
for i in $(seq 1 60); do docker logs tftv_batch 2>&1 | grep -q "match fetch scheduler start" && break; sleep 2; done
sleep "$SECS"
L="$(docker logs tftv_batch 2>&1)"
echo "RESULT perSecond=$PER_SEC latencyMs=$LAT seconds=$SECS env=$(docker exec tftv_batch sh -c 'echo $RIOT_RATE_LIMIT_BATCH_PER_SECOND')" \
     "mock=$(mock /__stats | python -c "import sys,json;d=json.load(sys.stdin);print('total=%d max1s=%d max120s=%d span=%s' % (d['total'],d['max1s'],d['max120s'],d['spanSec']))")" \
     "summonerDone=$(echo "$L" | grep -c 'Total matches found')" \
     "requeuedOnBudget=$(echo "$L" | grep -c 'budget exhausted while')" \
     "http429=$(echo "$L" | grep -c 'Rate limit exceeded')" \
     "errors=$(echo "$L" | grep -c 'Error processing queue')"
