# 측정 기록 (2026-10-11)

README에 적은 수치의 원본 출력입니다. 모두 같은 PC, 같은 로컬 Docker MySQL(매치 16,381건, 참가 기록 130,047건)에서 쟀습니다.

| 파일 | 내용 | 다시 재는 방법 |
| --- | --- | --- |
| `01-query-before-after.txt` | 조회 쿼리 개선 전(`f46b103`)·후(`759ac7d`)의 전적 페이지 측정 출력. 같은 스크립트로 한 번 더 돌린 결과 포함 | `bash docs/bench/run-bench.sh` |
| `02-index-off.txt` | `participant.pa_puuid` 인덱스를 숨긴 상태의 실행 계획과 측정 출력 | `bash docs/bench/run-bench-index.sh` |
| `03-index-on.txt` | 인덱스를 사용하는 상태의 실행 계획과 측정 출력 | 위와 같음 |
| `04-rate-limit-runs.txt` | 수집 10분 × 3회의 요약: 429 발생 시각, 완료 작업 수, API 호출 수 | 아래 참고 |
| `04-raw-*.txt` | 위 3회의 Batch 로그 원본 (API 키는 `REDACTED`로 기록됨) | |
| `05-batch-tests.txt` | 호출 제한기·수집 오류 처리 테스트 14개 실행 결과 | `cd batch && ./mvnw test -Dtest='RiotRateLimiterTest,MatchFetchServiceTest'` |

## 출력 읽는 법

`BENCH puuid=… matches=1000 … sql=12 … medianMs=147 minMs=117 maxMs=265`

- `matches` 해당 계정의 저장 전적 수
- `sql` 전적 페이지 요청 1회에서 실행된 SQL 수 (Hibernate Statistics)
- `medianMs` 워밍업 5회 뒤 15회 반복한 응답 시간의 중앙값

## 알아둘 점

- 응답 시간은 실행할 때마다 수십 ms씩 달라집니다. 인덱스 사용 상태의 1,000건 계정은 첫 측정에서 141ms, 이 폴더에 저장한 재측정에서 147ms였습니다. SQL 수는 항상 같습니다.
- 측정에는 Riot API 응답 시간이 들어 있지 않습니다. Riot 호출은 고정 응답으로 대체했습니다.
- 수집 10분 측정은 구성마다 한 번씩만 돌렸습니다. 세 번 모두 대기열 앞쪽이 소환사 작업(매치 목록을 100개씩 여러 번 조회)이었고, 매치 상세 저장 작업은 포함되지 않았습니다.
- API 호출 수는 로그의 "Total matches found" 건수로 계산한 값입니다(100개당 1회). 시작할 때 도는 랭커 목록 조회 몇 건은 세지 않았습니다.
- 수집 측정은 실제 Riot API에 붙어서 돌린 것이라 같은 조건을 그대로 다시 만들 수는 없습니다. 대기열 내용과 그날의 API 상태에 따라 달라집니다.
