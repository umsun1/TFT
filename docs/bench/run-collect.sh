#!/usr/bin/env bash
# Batch 수집기를 정해진 시간 동안 실제 Riot API에 붙여 돌리고, 429 횟수와 처리량을 요약한다.
# 사용법: Git Bash에서
#   bash docs/bench/run-collect.sh current 600        현재 작업 폴더의 코드로 10분
#   bash docs/bench/run-collect.sh 6c8a71f 600        특정 커밋(예: 제한기 도입 전)으로 10분
#   bash docs/bench/run-collect.sh current 600 2000   수집 주기를 2초로 바꿔서(제한기 도입 이후 코드만 가능)
# 준비물: tft_mysql(3307), tft_redis(6379) 실행 중, 유효한 Riot API 키가 든 batch/src/main/resources/application.properties
# 주의: 실제로 API를 호출하고 DB에 저장한다. 연달아 잴 때는 사이에 2분 이상 쉬어야 앞 실행의 호출 한도가 비워진다.
set -uo pipefail

REF="${1:-current}"
SECS="${2:-600}"
DELAY="${3:-}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="${TMPDIR:-/tmp}/tft-collect"
LOG="$WORK/$REF.log"
mkdir -p "$WORK"

if [ "$REF" = current ]; then
  SRC="$ROOT/batch"
else
  SRC="$WORK/src-$REF/batch"
  rm -rf "$WORK/src-$REF"; mkdir -p "$WORK/src-$REF"
  git -C "$ROOT" archive "$REF" batch | tar -x -C "$WORK/src-$REF"
  cp "$ROOT/batch/src/main/resources/application.properties" "$SRC/src/main/resources/"   # Git에 없는 설정 파일
fi

echo "빌드 중: $REF"
(cd "$SRC" && ./mvnw -q -DskipTests package > "$WORK/build-$REF.log" 2>&1) || { echo "빌드 실패: $WORK/build-$REF.log"; exit 1; }
JAR="$(ls "$SRC"/target/batch-*.jar | head -1)"

DBPW="$(grep '^MYSQL_ROOT_PASSWORD=' "$ROOT/.env" | cut -d= -f2- | tr -d '\r"')"
count() { docker exec tft_mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N tft_db -e "select count(*) from game_info" 2>/dev/null'; }
BEFORE="$(count)"

# 명령줄 인자는 환경변수보다 우선하므로, PC에 다른 프로젝트용 SPRING_DATASOURCE_* 가 있어도 이 DB에 붙는다
java -jar "$JAR" \
  --spring.datasource.url="jdbc:mysql://localhost:3307/tft_db?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Seoul" \
  --spring.datasource.username=root --spring.datasource.password="$DBPW" \
  --spring.data.redis.host=localhost --spring.data.redis.port=6379 --server.port=8091 \
  --spring.task.scheduling.pool.size=3 \
  ${DELAY:+--batch.match-fetch.delay-ms=$DELAY} > "$LOG" 2>&1 &
PID=$!

for i in $(seq 1 90); do grep -q "match fetch scheduler start" "$LOG" 2>/dev/null && break; sleep 1; done
grep -q "match fetch scheduler start" "$LOG" || { echo "수집기가 시작되지 않았습니다: $LOG"; kill $PID 2>/dev/null; exit 1; }
echo "수집 시작. ${SECS}초 동안 돌립니다."
sleep "$SECS"

WINPID="$(cat /proc/$PID/winpid 2>/dev/null || true)"
kill $PID 2>/dev/null
[ -n "$WINPID" ] && taskkill //PID "$WINPID" //F >/dev/null 2>&1
sleep 3

echo "=== $REF, ${SECS}초 ${DELAY:+(주기 ${DELAY}ms)}"
echo "429 발생:            $(grep -c 'Rate limit exceeded' "$LOG")회"
grep 'Rate limit exceeded' "$LOG" | cut -c1-19 | sed 's/^/  /'
echo "저장한 매치:         $(( $(count) - BEFORE ))건"
echo "매치 상세 작업:      $(grep -c 'Fetching details for MatchID' "$LOG")건"
grep -o "Total matches found for [^:]*: [0-9]*" "$LOG" | awk '{n=$NF; calls+=int(n/100)+1; c++} END {print "소환사 작업:         "c+0"건 (매치 목록 조회 "calls+0"회)"}'
echo "한도 소진으로 미룸:  $(grep -c 'budget exhausted while' "$LOG")건"
echo "오류:                $(grep -c 'Error processing queue' "$LOG")건"
echo "로그 원본: $LOG"
