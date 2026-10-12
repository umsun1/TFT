#!/usr/bin/env bash
# 검증용 스택을 빌드하고 DB·Redis·가짜 Riot·Web을 띄운다. (Batch는 각 검증 스크립트가 띄운다)
set -euo pipefail
source "$(dirname "$0")/common.sh"
[ -f "$VERIFY_DIR/certs/truststore.jks" ] || { echo "먼저 gen-certs.sh 를 실행하세요"; exit 1; }
dc build web batch
dc up -d db redis mock-riot web
for i in $(seq 1 90); do docker logs tftv_web 2>&1 | grep -q "Started WebApplication" && break; sleep 2; done
docker ps --filter name=tftv_ --format '{{.Names}}\t{{.Status}}'
echo "Riot 호스트가 가리키는 곳: $(docker exec tftv_web sh -c 'getent hosts kr.api.riotgames.com')"
echo "확인용 검색: $(curl -s -m 30 -o /dev/null -w '%{http_code}' "$WEB_URL/summoner/kr/sanity/KR1")  가짜 서버 수신: $(mock /__stats)"
