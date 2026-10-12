#!/usr/bin/env bash
# 가짜 Riot 서버용 테스트 인증서와, 그 인증서를 신뢰하는 자바 truststore를 만든다. Docker만 있으면 된다.
# 결과물(certs/)은 검증 전용이며 Git에 올리지 않는다.
set -euo pipefail
source "$(dirname "$0")/common.sh"
mkdir -p "$VERIFY_DIR/certs"

# 1) Riot API 호스트 이름으로 된 자체 서명 인증서
docker run --rm -v "$VERIFY_DIR/certs:/certs" alpine:3 sh -c '
  apk add --no-cache openssl >/dev/null &&
  openssl req -x509 -newkey rsa:2048 -nodes -days 30 \
    -keyout /certs/mock.key -out /certs/mock.crt -subj "/CN=mock-riot" \
    -addext "subjectAltName=DNS:kr.api.riotgames.com,DNS:asia.api.riotgames.com" 2>/dev/null'

# 2) 애플리케이션과 같은 JRE 이미지의 기본 truststore에 위 인증서를 추가
#    (기본 인증서를 유지해야 Web이 시작할 때 외부에서 받는 정적 데이터도 계속 받을 수 있다)
docker run --rm -v "$VERIFY_DIR/certs:/certs" eclipse-temurin:17-jre-jammy sh -c '
  cp "$JAVA_HOME/lib/security/cacerts" /certs/truststore.jks && chmod 644 /certs/truststore.jks &&
  keytool -importcert -keystore /certs/truststore.jks -storepass changeit \
    -alias mockriot -file /certs/mock.crt -noprompt >/dev/null 2>&1'

ls "$VERIFY_DIR/certs"
