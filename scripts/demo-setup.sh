#!/usr/bin/env bash
# One-command demo setup: registry + broker + schemas + MI
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

RED='\033[0;31m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; BOLD='\033[1m'; NC='\033[0m'
info()    { echo -e "${CYAN}[INFO]${NC}  $*"; }
success() { echo -e "${GREEN}[OK]${NC}    $*"; }
error()   { echo -e "${RED}[ERROR]${NC} $*"; }

echo -e "${BOLD}"
echo "  Schema Validation Micro-Integration — Demo Setup"
echo -e "${NC}"

# ── Env file ──
if [[ ! -f .env ]]; then
  cp .env.example .env
  info "Created .env from .env.example (local broker defaults)"
else
  info "Using existing .env"
fi

# ── Schema Registry ──
info "Starting Schema Registry..."
./scripts/start-local-registry-native.sh >/dev/null 2>&1
echo -n "  Waiting for registry API..."
for i in $(seq 1 30); do
  if curl -sf http://localhost:18081/apis/registry/v3/system/info >/dev/null 2>&1; then
    echo " ready"
    break
  fi
  echo -n "."
  sleep 2
done
success "Schema Registry running on http://localhost:18081"

# ── Solace Broker ──
info "Starting local Solace broker..."
docker compose -f docker-compose.broker.yml up -d >/dev/null 2>&1
echo -n "  Waiting for broker SEMP API..."
for i in $(seq 1 60); do
  if curl -sf -u admin:admin http://localhost:8080/SEMP/v2/config/msgVpns/default >/dev/null 2>&1; then
    echo " ready"
    break
  fi
  echo -n "."
  sleep 3
done

# Enable guaranteed messaging on default client profile
curl -sf -u admin:admin -X PATCH http://localhost:8080/SEMP/v2/config/msgVpns/default/clientProfiles/default \
  -H 'Content-Type: application/json' \
  -d '{"allowGuaranteedMsgSendEnabled":true,"allowGuaranteedMsgReceiveEnabled":true,"allowGuaranteedEndpointCreateEnabled":true}' >/dev/null 2>&1
success "Solace broker running — SEMP: http://localhost:8080 (admin/admin)"

# ── Register Schemas ──
info "Registering demo schemas..."
./scripts/register-schemas.sh 2>&1 | grep -E "^Registered|^Manufacturing"
success "Schemas registered"

# ── Build MI ──
info "Building MI Docker image..."
docker compose -f docker-compose.local.yml build --quiet 2>&1
success "Image built: schema-validation-micro-integration:local"

# ── Start MI ──
info "Starting MI..."
docker compose -f docker-compose.local.yml up -d >/dev/null 2>&1
echo -n "  Waiting for MI health..."
for i in $(seq 1 30); do
  if curl -s http://localhost:8088/actuator/health 2>/dev/null | grep -q '"UP"'; then
    echo " healthy"
    break
  fi
  echo -n "."
  sleep 3
done
success "MI running on http://localhost:8088"

# ── Summary ──
echo ""
echo -e "${BOLD}  Demo Ready${NC}"
echo ""
echo "  Registry:  http://localhost:18081/apis/registry/v3"
echo "  Broker:    http://localhost:8080 (admin/admin)"
echo "  MI Health: http://localhost:8088/actuator/health"
echo "  MI Metrics:http://localhost:8088/actuator/metrics"
echo ""
echo "  Next steps:"
echo "    ./scripts/demo-live.sh                # Live event flow viewer"
echo "    ./scripts/demo-publish.sh pub-files valid   # Publish a valid PUB_FILES message"
echo "    ./performance/run-all.sh              # Performance test suite"
echo ""
