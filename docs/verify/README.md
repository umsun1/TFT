# 호출 제한 검증 환경

Web과 Batch를 실제 Docker 구성으로 함께 띄우고, Riot API 대신 가짜 서버에 요청을 보내 호출 제한이 지켜지는지 확인합니다.

- 실제 Riot API로는 요청이 나가지 않습니다. Riot 호스트 이름(`kr.api.riotgames.com`, `asia.api.riotgames.com`)을 가짜 서버 컨테이너로 돌립니다.
- 수집 DB와 운영 Redis를 쓰지 않습니다. 프로젝트 이름(`tftverify`), 컨테이너 이름(`tftv_*`), 포트, 볼륨이 모두 따로입니다.
- 애플리케이션 코드와 `docker-compose.yml`은 그대로 쓰고, `verify.override.yml`로 위 두 가지만 덮어씁니다.

## 준비물

- Docker Desktop (Compose v2.24 이상: 덮어쓰기 파일의 `!override` 문법)
- bash, curl, python (스크립트가 결과를 읽을 때 사용). Windows는 Git Bash
- 프로젝트 루트의 `.env`, 각 모듈의 `application.properties` (평소 실행에 쓰는 것 그대로. Riot 키는 검증 중 `mock-key`로 덮어씁니다)
- 비어 있어야 하는 포트: 18080(Web), 13307(MySQL), 16379(Redis)

## 순서

프로젝트 루트에서 실행합니다.

```bash
bash docs/verify/gen-certs.sh      # 1. 테스트 인증서와 truststore 생성 (최초 1회, 30일 유효)
bash docs/verify/up.sh             # 2. 이미지 빌드 후 DB·Redis·가짜 Riot·Web 실행 (첫 빌드는 5분 이상)
bash docs/verify/run_combined.sh 300          # 3. Web 검색 부하 + Batch 수집을 5분간 함께 실행
bash docs/verify/run_block_and_pause.sh       # 4. 429 차단 공유, Redis 무응답 시 동작
bash docs/verify/run_batch_only.sh 10 150 250 # 5. Batch만: 1초 한도 10, 응답 지연 150ms, 250초
bash docs/verify/down.sh           # 6. 정리 (tftverify 범위의 컨테이너·볼륨·이미지 삭제)
```

코드를 고친 뒤 다시 확인하려면 `up.sh`부터 다시 실행합니다(이미지를 새로 빌드합니다).

## 인증서가 필요한 이유

애플리케이션은 `https://kr.api.riotgames.com/...`처럼 주소가 코드에 고정돼 있습니다. 코드를 바꾸지 않고 가짜 서버로 보내려면 두 가지가 필요합니다.

1. 호스트 이름이 가짜 서버 IP를 가리키게 한다 → `verify.override.yml`의 `extra_hosts`
2. 가짜 서버의 HTTPS 인증서를 자바가 신뢰하게 한다 → `gen-certs.sh`가 그 호스트 이름으로 자체 서명 인증서를 만들고, JRE 기본 truststore 사본에 추가합니다. 컨테이너는 `JAVA_TOOL_OPTIONS`로 그 truststore를 씁니다.

`certs/`에는 개인키가 들어 있으므로 Git에 올리지 않습니다(`.gitignore`에 포함).

## 결과 읽는 법

가짜 서버는 요청이 **도착한 시각**을 기록합니다.

```
{"total": 285, "byClient": {"172.31.250.5": 76, "172.31.250.4": 209}, "max1s": 15, "max120s": 96, ...}
```

| 항목 | 뜻 |
| --- | --- |
| `byClient` | 컨테이너 IP별 요청 수. 스크립트가 어느 IP가 Web이고 Batch인지 함께 출력합니다 |
| `max1s` | 임의의 1초 구간에 도착한 최대 요청 수 |
| `max120s` | 임의의 2분 구간에 도착한 최대 요청 수 |

제한기는 **허가한 시각**으로 세고 가짜 서버는 도착한 시각으로 세기 때문에, 설정값보다 1~2회 많게 나올 수 있습니다(2026-10-12 측정: 설정 95에 96, 설정 80에 82, 설정 10에 11). 설정값을 Riot 한도(2분 100회, 1초 20회)보다 낮게 둔 이유입니다.

## 가짜 서버 제어

스크립트가 내부적으로 쓰는 주소입니다. `source docs/verify/common.sh` 후 `mock <경로>`로 직접 호출할 수 있습니다.

| 경로 | 동작 |
| --- | --- |
| `/__stats` | 지금까지의 요청 통계 |
| `/__reset` | 통계 초기화 |
| `/__429?count=1&retry=20` | 다음 1개 요청에 `Retry-After: 20`과 함께 429 응답 (`retry` 생략 시 헤더 없음) |
| `/__config?latencyMs=150&pages=12` | 응답 지연, 소환사 1명의 매치 목록 쪽수 |

## 한계

- 가짜 서버는 한도를 강제하지 않고 세기만 합니다. Riot의 실제 구간 계산 방식이나 엔드포인트별 한도는 재현하지 않습니다.
- 매치 상세는 항상 404로 응답하므로 매치 저장 경로는 검증하지 않습니다.
- 한 PC의 Docker 네트워크라 실제 네트워크 지연과 다릅니다. `latencyMs`로 흉내만 낼 수 있습니다.
