#!/bin/sh
# KAFKA_UI_USER / KAFKA_UI_PASSWORD_HASH (.env, git 미포함)로 /kafka-ui Basic Auth용
# htpasswd 파일을 컨테이너 기동 시 생성한다. 비밀번호는 평문이 아니라
# openssl passwd -apr1 로 미리 해시한 값을 KAFKA_UI_PASSWORD_HASH에 넣어 사용한다.

set -e

if [ -n "$KAFKA_UI_USER" ] && [ -n "$KAFKA_UI_PASSWORD_HASH" ]; then
  echo "${KAFKA_UI_USER}:${KAFKA_UI_PASSWORD_HASH}" > /etc/nginx/.htpasswd-kafka-ui
  echo "[kafka-ui-htpasswd] /etc/nginx/.htpasswd-kafka-ui generated for user '${KAFKA_UI_USER}'"
else
  echo "[kafka-ui-htpasswd] WARN: KAFKA_UI_USER / KAFKA_UI_PASSWORD_HASH not set - /kafka-ui basic auth will reject all requests" >&2
fi