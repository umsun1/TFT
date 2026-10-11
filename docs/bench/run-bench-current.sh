#!/usr/bin/env bash
# 현재 작업 폴더의 web 모듈로 전적 페이지 조회 성능을 측정한다(인덱스 추가 전후 비교 등).
# 사용법: Git Bash에서  bash docs/bench/run-bench-current.sh
# 준비물: Docker의 tft_mysql(3307), tft_redis(6379) 실행 중, JDK 17, 프로젝트 루트의 .env
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
TEST="$ROOT/web/src/test/java/com/tft/web/BenchCurrentTest.java"
LOG="${TMPDIR:-/tmp}/tft-bench-current.log"

# PC에 다른 프로젝트용 SPRING_DATASOURCE_* 환경변수가 있어도 이 DB를 보도록 덮어쓴다
export SPRING_DATASOURCE_USERNAME=root
export SPRING_DATASOURCE_URL="jdbc:mysql://localhost:3307/tft_db?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Seoul"
export SPRING_DATASOURCE_PASSWORD="$(grep '^MYSQL_ROOT_PASSWORD=' "$ROOT/.env" | cut -d= -f2- | tr -d '\r"')"

PUUIDS="$(docker exec -i tft_mysql sh -c 'mysql -uroot -p"$MYSQL_ROOT_PASSWORD" -N tft_db 2>/dev/null' <<'SQL' | awk '{print $1}' | head -4 | paste -sd,
select pa_puuid, count(*) c from participant group by pa_puuid
having c in (1000, 541, 316) or c = 100 order by c desc;
SQL
)"
[ -n "$PUUIDS" ] || { echo "측정할 계정을 찾지 못했습니다. tft_mysql이 실행 중인지 확인하세요."; exit 1; }

cp "$ROOT/docs/bench/BenchCurrentTest.java" "$TEST"
trap 'rm -f "$TEST"' EXIT     # 측정용 테스트는 끝나면 지운다(일반 테스트 실행에 섞이지 않게)
(cd "$ROOT/web" && ./mvnw -q test -Dtest=BenchCurrentTest \
    -DargLine="-Dbench.puuids=$PUUIDS -Dbench.warm=5 -Dbench.runs=15" > "$LOG" 2>&1) \
    || { echo "실패: $LOG 를 확인하세요"; exit 1; }
grep '^BENCH' "$LOG"
