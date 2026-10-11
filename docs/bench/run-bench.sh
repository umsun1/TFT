#!/usr/bin/env bash
# 전적 페이지 조회 성능을 두 커밋에서 같은 조건으로 측정한다.
# 사용법: Git Bash에서  bash docs/bench/run-bench.sh [변경전커밋] [변경후커밋]
# 준비물: Docker의 tft_mysql 컨테이너 실행 중(포트 3307), JDK 17, 프로젝트 루트의 .env
set -euo pipefail

BEFORE="${1:-f46b103}"
AFTER="${2:-759ac7d}"
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
WORK="${TMPDIR:-/tmp}/tft-bench"
HERE="$ROOT/docs/bench"

# DB 비밀번호는 .env에서 읽어 환경변수로만 넘긴다(파일에 남기지 않음)
export SPRING_DATASOURCE_PASSWORD="$(grep '^MYSQL_ROOT_PASSWORD=' "$ROOT/.env" | cut -d= -f2- | tr -d '\r"')"

# 저장 전적 수가 서로 다른 계정 4개를 고른다(1,000 / 541 / 316 / 100건)
PUUIDS="$(docker exec -i tft_mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N tft_db 2>/dev/null' <<'SQL' | awk '{print $1}' | head -4 | paste -sd,
select pa_puuid, count(*) c from participant group by pa_puuid
having c in (1000, 541, 316) or c = 100 order by c desc;
SQL
)"
[ -n "$PUUIDS" ] || { echo "측정할 계정을 찾지 못했습니다. tft_mysql이 실행 중인지 확인하세요."; exit 1; }

for c in "$BEFORE" "$AFTER"; do
  dir="$WORK/$c"
  rm -rf "$dir"; mkdir -p "$dir"
  git -C "$ROOT" archive "$c" web | tar -x -C "$dir"          # 해당 커밋의 소스만 꺼냄(작업 중인 파일은 건드리지 않음)
  cp "$HERE/BenchTest.java" "$dir/web/src/test/java/com/tft/web/BenchTest.java"
  echo "=== $c 측정 중 (첫 실행은 빌드 때문에 1~2분 걸립니다)"
  (cd "$dir/web" && ./mvnw -q test -Dtest=BenchTest \
      -DargLine="-Dbench.puuids=$PUUIDS -Dbench.warm=5 -Dbench.runs=15" > "$WORK/$c.log" 2>&1) \
      || { echo "실패: $WORK/$c.log 를 확인하세요"; exit 1; }
  grep '^BENCH' "$WORK/$c.log" | sed 's/ all=.*//'
done
