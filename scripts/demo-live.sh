#!/usr/bin/env bash
# Live event flow viewer — watches MI output topics in real time
# Run in one terminal, publish messages in another
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PRETTY_DUMP_VERSION="${PRETTY_DUMP_VERSION:-1.1.1}"
PRETTY_DUMP_BIN="${ROOT_DIR}/.runtime/solace-pretty-dump-release/solace-pretty-dump-${PRETTY_DUMP_VERSION}/bin/prettydump"

if [[ ! -f "${ROOT_DIR}/.env" ]]; then
  echo "Missing .env — run ./scripts/demo-setup.sh first"
  exit 1
fi

set -a
source "${ROOT_DIR}/.env"
set +a

: "${URL:?Missing URL in .env}"
: "${VPN:?Missing VPN in .env}"
: "${USER:?Missing USER in .env}"
: "${PWD:?Missing PWD in .env}"

# PrettyDump subscribes to the OUTPUT broker (where validated messages land)
# PrettyDump uses JCSMP (SMF), so translate wss:// cloud URLs to tcps:// SMF
PRETTY_URL="${SOLACE_OUTPUT_BROKER:-${URL}}"
PRETTY_URL="${PRETTY_URL//solace-broker/localhost}"
PRETTY_URL="${PRETTY_URL//55555/55554}"
if [[ "${PRETTY_URL}" == wss://* ]]; then
  PRETTY_URL="${PRETTY_URL/wss:\/\//tcps://}"
  PRETTY_URL="${PRETTY_URL/:443/:55443}"
fi
PRETTY_VPN="${SOLACE_OUTPUT_VPN:-${VPN}}"
PRETTY_USER="${SOLACE_OUTPUT_USERNAME:-${USER}}"
PRETTY_PASS="${SOLACE_OUTPUT_PASSWORD:-${PWD}}"

# Prepare PrettyDump if needed
"${ROOT_DIR}/scripts/prepare-pretty-dump.sh" >/dev/null 2>&1

MI_HTTP=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:8088/actuator/health" 2>/dev/null || echo "000")
if [[ "${MI_HTTP}" == "000" ]]; then
  echo "MI not reachable on localhost:8088 — run ./scripts/demo-setup.sh first"
  exit 1
fi

echo ""
echo "  Live Event Flow Viewer"
echo "  ─────────────────────────────────────────"
echo "  Output broker: ${PRETTY_VPN}"
echo "  Subscribing to:"
echo "    Acme/*/>/   (Acme IoT messages)"
echo "    Apex/*/>/   (Apex IoT messages)"
echo ""
echo "  Output topic = input topic. Validation result is in user properties:"
echo "    mi_validation_status = VALIDATED | REJECT | PROCESSING_ERROR"
echo ""
echo "  In another terminal, publish all demo events:"
echo ""
echo "    ./scripts/demo-publish.sh all"
echo ""
echo "  Or publish individually (see ./scripts/demo-publish.sh help)"
echo ""
echo "  Press Ctrl-C to stop."
echo "  ─────────────────────────────────────────"
echo ""

# Use tq: (temp queue) so PrettyDump receives persistent messages published by the MI
# Subscribe to all Acme and Apex topics (same-topic routing)
"${PRETTY_DUMP_BIN}" "${PRETTY_URL}" "${PRETTY_VPN}" "${PRETTY_USER}" "${PRETTY_PASS}" \
  "tq:Acme/>,Apex/>" 2 --ts
