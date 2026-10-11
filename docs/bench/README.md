# 측정 방법

README의 수치를 직접 다시 재는 방법입니다. 모두 Git Bash에서 프로젝트 루트(`TFT/`) 기준으로 실행합니다.

## 준비

1. Docker Desktop을 켭니다.
2. DB와 Redis만 띄웁니다. Web·Batch 컨테이너는 띄우지 않습니다(측정 중에 데이터가 바뀌고 API 한도를 나눠 쓰게 됩니다).
   ```bash
   docker start tft_mysql tft_redis
   ```
3. 루트의 `.env`에 `MYSQL_ROOT_PASSWORD`가 있어야 합니다. 스크립트가 여기서 DB 비밀번호를 읽습니다.

## 무엇을 어떻게 재는가

| 알고 싶은 것 | 명령 | 걸리는 시간 | Riot API 호출 |
| --- | --- | --- | --- |
| 조회 쿼리 개선 전후 (커밋 두 개 비교) | `bash docs/bench/run-bench.sh` | 3~5분 | 없음 |
| 현재 코드의 전적 페이지 성능 | `bash docs/bench/run-bench-current.sh` | 1~2분 | 없음 |
| 인덱스가 있을 때와 없을 때 | `bash docs/bench/run-bench-index.sh` | 2~4분 | 없음 |
| 수집 중 429 횟수와 처리량 | `bash docs/bench/run-collect.sh current 600` | 지정한 시간 + 빌드 | 실제로 호출 |
| 제한기·오류 처리 동작 | `cd batch && ./mvnw test -Dtest='RiotRateLimiterTest,MatchFetchServiceTest'` | 1분 | 없음 |

## 1. 전적 페이지 성능 (`run-bench*.sh`)

### 원리

브라우저로 재지 않고 테스트 코드 안에서 잽니다. 이유는 두 가지입니다. 외부(Riot API, 네트워크) 시간이 섞이지 않고, SQL이 몇 번 나갔는지를 정확히 셀 수 있습니다.

1. 스프링 애플리케이션을 실제 DB에 연결해 띄웁니다.
2. `MockMvc`로 전적 페이지 주소(`/summoner/kr/...`)를 요청합니다. 컨트롤러 → 서비스 → DB 조회 → Thymeleaf 렌더링이 실제 코드 그대로 실행됩니다.
3. Riot API를 부르는 부분만 고정된 응답을 돌려주는 가짜로 바꿉니다.
4. 요청 직전에 Hibernate 통계를 0으로 만들고, 요청 직후 통계에서 SQL 실행 수를 읽습니다.
5. 처음 5번은 버리고(JVM이 덜 데워져 느림) 15번을 재서 가운데 값(중앙값)을 씁니다.
6. 테스트 전체가 한 트랜잭션이고 끝나면 롤백되므로 DB에는 아무것도 남지 않습니다.

측정 코드는 `BenchTest.java`(옛 커밋용), `BenchCurrentTest.java`(현재 코드용)입니다. 스크립트가 이 파일을 테스트 폴더에 복사해 `./mvnw test`로 실행하고, 끝나면 지웁니다.

### 출력 읽는 법

```
BENCH puuid=EIfGM1YQ matches=1000 status=200 htmlBytes=1356797 sql=12 entitiesLoaded=3627 medianMs=147 minMs=117 maxMs=265
```

| 항목 | 뜻 |
| --- | --- |
| `matches` | 그 계정의 저장 전적 수. 스크립트가 1,000 / 541 / 316 / 100건인 계정을 골라 4줄을 출력합니다 |
| `status` | 200이어야 정상입니다 |
| `sql` | 페이지 요청 1회에 실행된 SQL 수. 실행할 때마다 같습니다 |
| `entitiesLoaded` | DB에서 읽어 만든 엔티티 수 |
| `medianMs` | 응답 시간 중앙값. PC 상태에 따라 수십 ms씩 달라집니다 |

### 다른 커밋을 비교하려면

```bash
bash docs/bench/run-bench.sh <변경전커밋> <변경후커밋>
```

`git archive`로 해당 커밋의 소스를 임시 폴더에 꺼내 빌드하므로, 작업 중인 파일과 브랜치는 건드리지 않습니다. 단, `BenchTest.java`는 `f46b103`·`759ac7d` 시점의 코드 구조에 맞춰져 있어 훨씬 뒤의 커밋에는 맞지 않습니다. 최근 코드는 `run-bench-current.sh`로 잽니다.

### 인덱스 전후 (`run-bench-index.sh`)

인덱스를 지우지 않고 MySQL의 `INVISIBLE` 기능으로 잠시 숨긴 뒤 재고, 다시 켜고 잽니다. 통계 쿼리의 실행 계획(`EXPLAIN`)도 함께 출력합니다. 중간에 실패해도 인덱스는 다시 켜집니다.

실행 계획에서 볼 것: `key` 열에 `idx_participant_puuid`가 있는지, `rows` 열(읽을 것으로 예상하는 행 수)이 얼마나 줄었는지.

## 2. 수집 중 429 횟수 (`run-collect.sh`)

Batch 수집기를 실제 Riot API에 붙여 정해진 시간 동안 돌리고, 로그에서 숫자를 셉니다.

```bash
bash docs/bench/run-collect.sh current 600        # 현재 코드로 10분
bash docs/bench/run-collect.sh 6c8a71f 600        # 제한기 도입 전 커밋으로 10분
```

| 출력 | 세는 방법 |
| --- | --- |
| 429 발생 | 로그의 `Rate limit exceeded` 줄 수 |
| 저장한 매치 | 실행 전후 `game_info` 행 수의 차이 |
| 소환사 작업 / 매치 목록 조회 횟수 | 로그의 `Total matches found for …: N` 줄. 목록을 100개씩 가져오므로 N으로 호출 횟수를 계산 |
| 한도 소진으로 미룸 | 로그의 `budget exhausted while` 줄 수 |

주의할 점

- 유효한 Riot API 키가 `batch/src/main/resources/application.properties`에 있어야 합니다. 개발 키는 24시간마다 만료됩니다.
- 실제로 API를 호출하고 DB에 저장합니다.
- 두 번 이상 연달아 잴 때는 사이에 2분 이상 쉽니다. 앞 실행이 쓴 호출 한도(2분 구간)가 비워져야 공정합니다.
- 그날의 대기열 내용에 따라 결과가 달라집니다. 대기열 앞쪽이 소환사 작업이면 작업 하나가 여러 번 호출하고, 매치 작업이면 한 번만 호출합니다.
- 수집기는 시작할 때 랭커 수집과 LP 갱신도 함께 돕니다. 최근 24시간 안에 LP 기록이 많이 쌓여 있으면 LP 갱신이 한도를 많이 써서 매치 수집 수치가 낮게 나옵니다.

## 3. 측정이 멈춘 것처럼 보일 때

Web 모듈은 시작할 때 외부에서 정적 데이터(약 24MB)를 내려받습니다. 이 다운로드에 타임아웃이 없어 가끔 멈춥니다. 3분 넘게 출력이 없으면 `Ctrl+C`로 끊고 다시 실행하세요. 남은 자바 프로세스는 작업 관리자에서 `java.exe`를 종료하면 됩니다.

## 결과 기록

측정할 때마다 `results/날짜/`에 원본 출력을 남깁니다. `results/2026-10-11/`에 README 수치의 근거가 있습니다.
