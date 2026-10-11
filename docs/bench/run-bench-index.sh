#!/usr/bin/env bash
# participant.pa_puuid 인덱스의 효과를 같은 코드로 비교한다.
# 인덱스를 잠시 숨겼다가(INVISIBLE) 다시 켜므로 데이터와 스키마는 그대로다.
# 사용법: Git Bash에서  bash docs/bench/run-bench-index.sh [결과 폴더]
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
OUT="${1:-$ROOT/docs/bench/results/$(date +%F)}"
mkdir -p "$OUT"

q() { docker exec -i tft_mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -t tft_db 2>/dev/null'; }
EXPLAIN="explain select count(*), avg(p.pa_placement) from participant p join game_info g on g.ga_num = p.pa_ga_num
where p.pa_puuid = (select pa_puuid from (select pa_puuid, count(*) c from participant group by pa_puuid having c = 1000 limit 1) t)
and g.queue_id = 1100;"

# 중간에 실패해도 인덱스는 반드시 다시 켠다
trap 'echo "alter table participant alter index idx_participant_puuid visible;" | q' EXIT

measure() {
  echo "# $1 - 코드 $(git -C "$ROOT" rev-parse --short HEAD), $(date '+%F %T')"
  echo "## 통계 쿼리 실행 계획"
  echo "$EXPLAIN" | q
  echo "## 전적 페이지 측정"
  bash "$ROOT/docs/bench/run-bench-current.sh"
}

echo "alter table participant alter index idx_participant_puuid invisible;" | q
measure "인덱스 숨김" | tee "$OUT/02-index-off.txt"
echo "alter table participant alter index idx_participant_puuid visible;" | q
measure "인덱스 사용" | tee "$OUT/03-index-on.txt"
