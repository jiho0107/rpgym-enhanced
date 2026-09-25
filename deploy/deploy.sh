#!/bin/bash

set -e

PROJECT_PATH="/home/ubuntu/rp-gym"
COMPOSE_FILE="${PROJECT_PATH}/docker-compose-prod.yml"
NGINX_CONF="${PROJECT_PATH}/nginx/service-url.inc"
DRAIN_SECONDS=10
NGINX_READY_RETRIES=15
NGINX_READY_INTERVAL=2
TARGET_HEALTH_STABLE_CHECKS=3

cd "${PROJECT_PATH}"

# 중복 배포 방지
exec 9>/tmp/rp-gym-deploy.lock

if ! flock -n 9; then
    echo "Another deployment is already running."
    exit 1
fi

ENV_FILE="${PROJECT_PATH}/.env"

if [ ! -f "${ENV_FILE}" ]; then
    echo ".env file not found: ${ENV_FILE}"
    exit 1
fi

for VAR in \
    ZIPKIN_UI_USER \
    ZIPKIN_UI_PASSWORD_HASH \
    KAFKA_UI_USER \
    KAFKA_UI_PASSWORD_HASH \
    GRAFANA_ROOT_URL \
    GRAFANA_ADMIN_USER \
    GRAFANA_ADMIN_PASSWORD \
    ZIPKIN_MEM_MAX_SPANS \
    TRACING_SAMPLING_PROBABILITY
do
    if ! grep -qE "^${VAR}=.+" "${ENV_FILE}"; then
        echo "Required environment variable is missing or empty: ${VAR}"
        exit 1
    fi
done

echo "Blue-Green Deploy Start"

if [ ! -f "${NGINX_CONF}" ]; then
    echo "Nginx config not found: ${NGINX_CONF}"
    exit 1
fi

CURRENT=$(grep -E "server gateway-(blue|green):19001;" "${NGINX_CONF}" \
    | awk '{print $2}' \
    | cut -d':' -f1)

if [ "${CURRENT}" = "gateway-blue" ]; then
    TARGET="green"
    BEFORE="blue"
elif [ "${CURRENT}" = "gateway-green" ]; then
    TARGET="blue"
    BEFORE="green"
else
    echo "Invalid current gateway configuration."
    echo "Current: ${CURRENT}"
    exit 1
fi

echo "Current Environment : ${BEFORE}"
echo "Deploy Target       : ${TARGET}"

TARGET_SERVICES=(
    "eureka-server-${TARGET}"
    "gateway-${TARGET}"
    "user-service-${TARGET}"
    "health-service-${TARGET}"
    "game-service-${TARGET}"
    "notification-service-${TARGET}"
)

BEFORE_SERVICES=(
    "eureka-server-${BEFORE}"
    "gateway-${BEFORE}"
    "user-service-${BEFORE}"
    "health-service-${BEFORE}"
    "game-service-${BEFORE}"
    "notification-service-${BEFORE}"
)

# 이전 환경 존재 여부 확인
if docker inspect -f '{{.State.Running}}' "rp-gym-gateway-${BEFORE}" 2>/dev/null \
    | grep -q '^true$'; then
    HAS_BEFORE_ENV=true
else
    HAS_BEFORE_ENV=false
fi

# 배포 시작 시점의 Nginx 상태 저장
if docker inspect -f '{{.State.Running}}' rp-gym-nginx 2>/dev/null \
    | grep -q '^true$'; then
    NGINX_WAS_RUNNING=true
else
    NGINX_WAS_RUNNING=false
fi

if [ "${HAS_BEFORE_ENV}" = true ]; then
    echo "Previous environment detected: gateway-${BEFORE}"
else
    echo "No previous environment detected. Treating as initial deployment."
fi

# 롤백
rollback() {
    echo "Rollback Start"

    # 이전 환경이 존재하는 경우
    if [ "${HAS_BEFORE_ENV}" = true ]; then
        echo "Restoring previous environment: gateway-${BEFORE}"

        cat > "${NGINX_CONF}" <<EOF
upstream gateway {
    server gateway-${BEFORE}:19001;
}
EOF

        # 배포 시작 당시 Nginx가 실행 중이었다면
        # 설정을 되돌린 후 reload
        if [ "${NGINX_WAS_RUNNING}" = true ]; then
            if ! docker exec rp-gym-nginx nginx -t; then
                echo "Nginx rollback configuration test failed."
                return 1
            fi

            if ! docker exec rp-gym-nginx nginx -s reload; then
                echo "Nginx rollback reload failed."
                return 1
            fi

        # 배포 시작 당시 Nginx가 꺼져 있었다면
        # 이전 환경 설정으로 Nginx를 새로 시작
        else
            echo "Nginx was not running before deployment."

            docker compose -f "${COMPOSE_FILE}" stop nginx || true

            if ! docker compose -f "${COMPOSE_FILE}" up -d nginx; then
                echo "Nginx rollback start failed."
                return 1
            fi

            if ! docker exec rp-gym-nginx nginx -t; then
                echo "Nginx rollback configuration test failed."
                return 1
            fi
        fi

        echo "Nginx rollback configuration restored."

        # 이전 Gateway가 실제로 정상인지 확인
        if ! wait_for_previous_gateway; then
            echo "Previous Gateway verification failed."
            return 1
        fi

        # Nginx를 통해 이전 환경으로 실제 요청이 전달되는지 확인
        if ! wait_for_nginx_traffic; then
            echo "Nginx rollback traffic verification failed."
            return 1
        fi

        echo "Nginx rollback success."

    # 이전 환경이 없는 최초 배포인 경우
    else
        echo "No previous environment exists."

        # 최초 배포 전 Nginx가 꺼져 있었다면
        # 이번 배포에서 띄운 Nginx도 정리
        if [ "${NGINX_WAS_RUNNING}" = false ]; then
            echo "Stopping Nginx started by this deployment."
            docker compose -f "${COMPOSE_FILE}" stop nginx || true
        fi

        # 최초 상태의 service-url.inc 유지
        cat > "${NGINX_CONF}" <<EOF
upstream gateway {
    server gateway-${BEFORE}:19001;
}
EOF
    fi

    echo "Stopping target environment: ${TARGET}"

    docker compose -f "${COMPOSE_FILE}" stop \
        "${TARGET_SERVICES[@]}" || true

    echo "Rollback Success"
    return 0
}

# 신규 Gateway 상태 확인
wait_for_target_gateway() {
    local CONTAINER="rp-gym-gateway-${TARGET}"
    local HTTP_CODE
    local SUCCESS_COUNT=0

    echo "Waiting for gateway-${TARGET} health..."

    for ((i=1; i<=NGINX_READY_RETRIES; i++)); do

        HTTP_CODE=$(docker exec "${CONTAINER}" curl \
            --max-time 2 \
            -s \
            -o /dev/null \
            -w "%{http_code}" \
            http://localhost:19091/actuator/health 2>/dev/null || true)

        if [ "${HTTP_CODE}" = "200" ]; then
            SUCCESS_COUNT=$((SUCCESS_COUNT + 1))

            echo "Target Gateway health is UP. (${SUCCESS_COUNT}/${TARGET_HEALTH_STABLE_CHECKS})"

            if [ "${SUCCESS_COUNT}" -ge "${TARGET_HEALTH_STABLE_CHECKS}" ]; then
                echo "Target Gateway health is stable."
                return 0
            fi
        else
            SUCCESS_COUNT=0

            echo "Target Gateway health is not ready: ${HTTP_CODE:-000} (attempt ${i}/${NGINX_READY_RETRIES})"
        fi

        sleep "${NGINX_READY_INTERVAL}"
    done

    echo "Target Gateway health check failed."
    return 1
}

# 이전 Gateway 상태 확인
wait_for_previous_gateway() {
    local CONTAINER="rp-gym-gateway-${BEFORE}"
    local HTTP_CODE

    echo "Waiting for gateway-${BEFORE} health..."

    for ((i=1; i<=NGINX_READY_RETRIES; i++)); do

        HTTP_CODE=$(docker exec "${CONTAINER}" curl \
            --max-time 2 \
            -s \
            -o /dev/null \
            -w "%{http_code}" \
            http://localhost:19091/actuator/health 2>/dev/null || true)

        if [ "${HTTP_CODE}" = "200" ]; then
            echo "Previous Gateway health is UP."
            return 0
        fi

        echo "Previous Gateway health is not ready: ${HTTP_CODE:-000} (attempt ${i}/${NGINX_READY_RETRIES})"

        sleep "${NGINX_READY_INTERVAL}"
    done

    echo "Previous Gateway health check failed."
    return 1
}

# Nginx 트래픽 확인
wait_for_nginx_traffic() {
    local HTTP_CODE

    echo "Waiting for Nginx traffic..."

    for ((i=1; i<=NGINX_READY_RETRIES; i++)); do

        HTTP_CODE=$(curl \
            --max-time 2 \
            -s \
            -o /dev/null \
            -w "%{http_code}" \
            http://localhost/ || true)

        if [[ "${HTTP_CODE}" =~ ^[1-4][0-9][0-9]$ ]]; then
            echo "Nginx traffic is available. HTTP status: ${HTTP_CODE}"
            return 0
        fi

        echo "Nginx traffic is not available yet: ${HTTP_CODE:-000} (attempt ${i}/${NGINX_READY_RETRIES})"

        sleep "${NGINX_READY_INTERVAL}"
    done

    echo "Nginx traffic failed to become available."
    return 1
}

# 공용 인프라 실행
echo "Start Shared Infrastructure"

docker compose -f "${COMPOSE_FILE}" up -d \
    postgres-user \
    postgres-health \
    postgres-game \
    postgres-notification \
    kafka \
    kafka-ui \
    redis \
    prometheus \
    grafana \
    loki \
    alloy \
    zipkin

# 신규 환경 실행
echo "Build & Start ${TARGET}"

if ! docker compose -f "${COMPOSE_FILE}" up -d \
    --build \
    --wait \
    --wait-timeout 300 \
    "${TARGET_SERVICES[@]}"; then

    echo "Target environment failed to become healthy."

    docker compose -f "${COMPOSE_FILE}" ps

    docker compose -f "${COMPOSE_FILE}" logs \
        --tail=100 \
        "${TARGET_SERVICES[@]}" || true

    echo "Stopping failed target environment."

    docker compose -f "${COMPOSE_FILE}" stop \
        "${TARGET_SERVICES[@]}" || true

    exit 1
fi

echo "Target environment is healthy."

# Nginx 대상 환경 전환
echo "Switch Nginx to gateway-${TARGET}"

cat > "${NGINX_CONF}" <<EOF
upstream gateway {
    server gateway-${TARGET}:19001;
}
EOF

# 최초 Nginx 기동
if [ "${NGINX_WAS_RUNNING}" = false ]; then

    echo "Nginx is not running. Starting Nginx..."

    if ! docker compose -f "${COMPOSE_FILE}" up -d nginx; then
        echo "Nginx start failed."

        if ! rollback; then
            echo "CRITICAL: Automatic rollback failed."
        fi

        exit 1
    fi

    echo "Testing Nginx configuration"

    if ! docker exec rp-gym-nginx nginx -t; then
        echo "Nginx configuration test failed."

        if ! rollback; then
            echo "CRITICAL: Automatic rollback failed."
        fi

        exit 1
    fi

    echo "Nginx started with gateway-${TARGET}"

# 기존 Nginx 전환
else

    echo "Testing Nginx configuration"

    if ! docker exec rp-gym-nginx nginx -t; then
        echo "Nginx configuration test failed."

        if ! rollback; then
            echo "CRITICAL: Automatic rollback failed."
        fi

        exit 1
    fi

    echo "Reload Nginx"

    if ! docker exec rp-gym-nginx nginx -s reload; then
        echo "Nginx reload failed."

        if ! rollback; then
            echo "CRITICAL: Automatic rollback failed."
        fi

        exit 1
    fi

    echo "Nginx switched to gateway-${TARGET}"
fi

# 신규 Gateway 상태 검증
echo "Verify target Gateway"

if ! wait_for_target_gateway; then
    echo "Target environment verification failed."

    if ! rollback; then
        echo "CRITICAL: Automatic rollback failed."
    fi

    exit 1
fi

# Nginx 트래픽 검증
echo "Verify Nginx traffic"

if ! wait_for_nginx_traffic; then
    echo "Nginx traffic verification failed."

    if ! rollback; then
        echo "CRITICAL: Automatic rollback failed."
    fi

    exit 1
fi

echo "Target environment verification successful."

# 기존 연결 종료 대기
if [ "${HAS_BEFORE_ENV}" = true ]; then
    echo "Connection Draining: ${DRAIN_SECONDS}s"
    sleep "${DRAIN_SECONDS}"

    echo "Stop ${BEFORE} Environment"

    docker compose -f "${COMPOSE_FILE}" stop \
        "${BEFORE_SERVICES[@]}"

else
    echo "No previous environment to stop."
fi

echo "Deploy Success"
echo "Active Environment : ${TARGET}"