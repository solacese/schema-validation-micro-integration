#!/usr/bin/env bash
# One-command local demo: registry + broker + schemas + MI.
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"
source "${ROOT_DIR}/scripts/lib/env.sh"

for required in docker python3 curl; do
  command -v "${required}" >/dev/null || { echo "Missing prerequisite: ${required}" >&2; exit 1; }
done
docker ps >/dev/null
docker compose version >/dev/null
if [[ ! -f .env ]]; then
  cp .env.example .env
  echo "Created .env from .env.example"
fi
load_project_env

wait_http() {
  local label="$1" url="$2" attempts="$3"
  shift 3
  echo "Waiting for ${label} at ${url}..."
  for ((attempt=1; attempt<=attempts; attempt++)); do
    if curl --connect-timeout 2 --max-time 5 -fsS "$@" "${url}" >/dev/null 2>&1; then
      return 0
    fi
    sleep 2
  done
  echo "Timed out waiting for ${label}. Inspect docker compose logs before retrying." >&2
  return 1
}

./scripts/start-local-registry-native.sh
REGISTRY_HOST_URL="http://localhost:${REGISTRY_API_HOST_HTTP_PORT:-18081}/apis/registry/v3"
wait_http "Schema Registry" "${REGISTRY_HOST_URL}/system/info" 60
# Check an authenticated endpoint too: system/info alone does not prove IdP readiness.
wait_http "Schema Registry authentication" "${REGISTRY_HOST_URL}/groups" 60 \
  -u "sr-developer:${DEVELOPER_PASSWORD:-devPassword}"

docker compose -f docker-compose.broker.yml up -d
BROKER_SEMP_HOST_URL="${BROKER_SEMP_HOST_URL:-http://localhost:8080}"
wait_http "broker SEMP" "${BROKER_SEMP_HOST_URL}/SEMP/v2/config/msgVpns/default" 90 -u admin:admin
curl --connect-timeout 2 --max-time 10 -fsS -u admin:admin -X PATCH \
  "${BROKER_SEMP_HOST_URL}/SEMP/v2/config/msgVpns/default/clientProfiles/default" \
  -H 'Content-Type: application/json' \
  -d '{"allowGuaranteedMsgSendEnabled":true,"allowGuaranteedMsgReceiveEnabled":true,"allowGuaranteedEndpointCreateEnabled":true}' >/dev/null

# Host-side seeding uses developer credentials, separate from the MI's readonly account.
SCHEMA_REGISTRY_BASE_URL="${REGISTRY_HOST_URL}" \
SCHEMA_REGISTRY_USERNAME=sr-developer \
SCHEMA_REGISTRY_PASSWORD="${DEVELOPER_PASSWORD:-devPassword}" ./scripts/register-schemas.sh

docker compose -f docker-compose.local.yml up -d --build
wait_http "micro-integration health" "http://localhost:8088/actuator/health" 60
printf '\nDemo ready\n  Registry: %s\n  Broker: %s\n  MI: http://localhost:8088/actuator/health\n' "${REGISTRY_HOST_URL}" "${BROKER_SEMP_HOST_URL}"
echo "Run ./scripts/demo-live.sh and ./scripts/demo-publish.sh all in separate terminals."
