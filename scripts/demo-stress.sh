#!/usr/bin/env bash
# Stress test: publish at a sustained rate and monitor throughput in real time
#
# Usage:
#   ./scripts/demo-stress.sh              # 1000 msg/sec for 60s (defaults)
#   ./scripts/demo-stress.sh 2000 30      # 2000 msg/sec for 30s
#   ./scripts/demo-stress.sh 500 120      # 500 msg/sec for 120s
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDKPERF="${ROOT_DIR}/performance/sdkperf-jcsmp-10.29.1/sdkperf_java.sh"

RATE="${1:-1000}"
DURATION="${2:-60}"
TOTAL=$((RATE * DURATION))

# ── Colors ──
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; DIM='\033[2m'; NC='\033[0m'

# ── Load env ──
if [[ ! -f "${ROOT_DIR}/.env" ]]; then
  echo "Missing .env — run ./scripts/demo-setup.sh first"
  exit 1
fi
set -a; source "${ROOT_DIR}/.env"; set +a

# ── Check MI ──
MI_CONTAINER="${MI_CONTAINER:-schema-validation-micro-integration}"
MI_HTTP=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:8088/actuator/health" 2>/dev/null || echo "000")
if [[ "${MI_HTTP}" == "000" ]]; then
  echo "MI not reachable on localhost:8088 — run ./scripts/demo-setup.sh first"
  exit 1
fi

# ── Build SDKPerf connection URL ──
build_sdkperf_url() {
  local url="${SOLACE_INPUT_BROKER:-${URL}}"
  url="${url//solace-broker/localhost}"
  url="${url//55555/55554}"
  if [[ "${url}" == wss://* ]]; then
    url="${url/wss:\/\//tcps://}"
    url="${url/:443/:55443}"
  fi
  echo "${url}"
}

SDKPERF_URL=$(build_sdkperf_url)
SDKPERF_VPN="${SOLACE_INPUT_VPN:-${VPN}}"
SDKPERF_USER="${SOLACE_INPUT_USERNAME:-${USER}}"
SDKPERF_PASS="${SOLACE_INPUT_PASSWORD:-${PWD}}"

# ── Check SDKPerf ──
if [[ ! -x "${SDKPERF}" ]]; then
  echo "SDKPerf not found at ${SDKPERF}"
  echo "See performance/README.md for setup instructions"
  exit 1
fi

# ── Payloads ──
# Interleave: 99 valid PUB_FILES, 1 invalid (missing message_id)
VALID="${ROOT_DIR}/samples/json/acme-pub-files.valid.json"
INVALID="${ROOT_DIR}/samples/json/acme-pub-files.invalid.json"
PFL_LIST=""
for ((i=0; i<99; i++)); do PFL_LIST+="${VALID},"; done
PFL_LIST+="${INVALID}"

# Acme IoT topic
PUB_TOPIC="Acme/SA/PMNO-001/DATASCIENCE/JSON-v1/FromEqt/PUB_FILES"

# Schema user properties
SCHEMA_PROPS="-cpl=String,schema_group,manufacturing-iot,String,schema_name,pub-files,String,schema_version,1.0.0"

# ── Banner ──
echo ""
echo -e "${BOLD}  Stress Test${NC}"
echo "  ─────────────────────────────────────────────────────────────────────────────────────"
echo -e "  Rate: ${BOLD}${RATE}${NC} msg/sec   Duration: ${BOLD}${DURATION}${NC}s   Total: ${BOLD}${TOTAL}${NC} messages"
echo -e "  Topic: ${DIM}${PUB_TOPIC}${NC}"
echo -e "  Payload: ${DIM}99% valid / 1% invalid (pub-files)${NC}"
echo "  ─────────────────────────────────────────────────────────────────────────────────────"
echo ""

# ── Capture log marker ──
LOG_MARKER=$(python3 -c "from datetime import datetime,timezone; print(datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'))")
sleep 1

# ── Launch SDKPerf in background ──
SDKPERF_LOG=$(mktemp)
"${SDKPERF}" \
  "-cip=${SDKPERF_URL}" "-cu=${SDKPERF_USER}@${SDKPERF_VPN}" "-cp=${SDKPERF_PASS}" \
  -ptl="${PUB_TOPIC}" \
  -pfl="${PFL_LIST}" \
  ${SCHEMA_PROPS} \
  -mt=persistent \
  -mn="${TOTAL}" \
  -mr="${RATE}" \
  -ped=30 > "${SDKPERF_LOG}" 2>&1 &
PUB_PID=$!
trap "kill ${PUB_PID} 2>/dev/null; rm -f ${SDKPERF_LOG}" EXIT

# ── Live Monitor ──
# Print one line per second showing publish and MI processing progress

count_mi_events() {
  local count
  count=$(docker logs --since "${LOG_MARKER}" "${MI_CONTAINER}" 2>&1 | grep -c "event=$1" 2>/dev/null) || count=0
  echo "${count}"
}

# Header
printf "${BOLD}%8s  %8s  %10s  %10s  %10s  %10s  %10s  %10s${NC}\n" \
  "elapsed" "pub/s" "published" "+pub" "processed" "+proc" "+valid" "MI msg/s"

PREV_PUB=0
PREV_MI=0
PREV_VALID=0
START_SEC=$(date +%s)

while true; do
  sleep 1

  NOW_SEC=$(date +%s)
  ELAPSED=$((NOW_SEC - START_SEC))
  MINUTES=$((ELAPSED / 60))
  SECONDS_PART=$((ELAPSED % 60))
  ELAPSED_FMT=$(printf "%dm%02ds" "${MINUTES}" "${SECONDS_PART}")

  # Estimate published messages from rate * elapsed (SDKPerf doesn't stream progress)
  TOTAL_PUB_LINE=$(grep "Total Messages" "${SDKPERF_LOG}" 2>/dev/null | tail -1 || true)
  if [[ -n "${TOTAL_PUB_LINE}" ]]; then
    # SDKPerf finished — grab total
    CURR_PUB=$(echo "${TOTAL_PUB_LINE}" | grep -oE '[0-9]+' | head -1) || CURR_PUB=0
  else
    # Still running — estimate from rate * elapsed
    CURR_PUB=$((RATE * ELAPSED))
    if (( CURR_PUB > TOTAL )); then
      CURR_PUB=${TOTAL}
    fi
  fi
  DELTA_PUB=$((CURR_PUB - PREV_PUB))

  # Count MI processed events from Docker logs
  MI_VALID=$(count_mi_events "validated")
  MI_REJECTED=$(count_mi_events "rejected")
  MI_ERROR=$(count_mi_events "processing_error")
  CURR_MI=$((MI_VALID + MI_REJECTED + MI_ERROR))
  DELTA_MI=$((CURR_MI - PREV_MI))
  DELTA_VALID=$((MI_VALID - PREV_VALID))

  # Current MI throughput (average over full run)
  if (( ELAPSED > 0 )); then
    MI_RATE=$((CURR_MI / ELAPSED))
  else
    MI_RATE=0
  fi

  # Color the delta columns
  if (( DELTA_PUB > 0 )); then
    PUB_DELTA_COLOR="${GREEN}+${DELTA_PUB}${NC}"
  else
    PUB_DELTA_COLOR="${DIM}+0${NC}"
  fi

  if (( DELTA_MI > 0 )); then
    MI_DELTA_COLOR="${GREEN}+${DELTA_MI}${NC}"
  else
    MI_DELTA_COLOR="${YELLOW}+0${NC}"
  fi

  if (( DELTA_VALID > 0 )); then
    VALID_DELTA_COLOR="${GREEN}+${DELTA_VALID}${NC}"
  else
    VALID_DELTA_COLOR="${DIM}+0${NC}"
  fi

  # Color the MI rate
  if (( MI_RATE >= RATE )); then
    RATE_COLOR="${GREEN}${MI_RATE}${NC}"
  elif (( MI_RATE >= RATE / 2 )); then
    RATE_COLOR="${YELLOW}${MI_RATE}${NC}"
  else
    RATE_COLOR="${RED}${MI_RATE}${NC}"
  fi

  # Current publish rate
  if kill -0 "${PUB_PID}" 2>/dev/null; then
    CUR_PUB_RATE="${RATE}"
  else
    CUR_PUB_RATE="0"
  fi

  printf "%8s  %8s  %10s  %10b  %10s  %10b  %10b  %10b\n" \
    "${ELAPSED_FMT}" \
    "${CUR_PUB_RATE}" \
    "${CURR_PUB}" \
    "${PUB_DELTA_COLOR}" \
    "${CURR_MI}" \
    "${MI_DELTA_COLOR}" \
    "${VALID_DELTA_COLOR}" \
    "${RATE_COLOR}"

  PREV_PUB=${CURR_PUB}
  PREV_MI=${CURR_MI}
  PREV_VALID=${MI_VALID}

  # Stop when publisher is done AND MI has caught up (or 60s extra timeout)
  if ! kill -0 "${PUB_PID}" 2>/dev/null; then
    if (( CURR_MI >= TOTAL )) || (( ELAPSED > DURATION + 60 )); then
      break
    fi
  fi
done

# ── Final summary ──
echo ""
echo "  ─────────────────────────────────────────────────────────────────────────────────────"
MI_VALID=$(count_mi_events "validated")
MI_REJECTED=$(count_mi_events "rejected")
MI_ERROR=$(count_mi_events "processing_error")
MI_TOTAL=$((MI_VALID + MI_REJECTED + MI_ERROR))

echo -e "  ${BOLD}Results${NC}"
echo -e "  Published:  ${TOTAL} messages at ${RATE} msg/sec"
echo -e "  Processed:  ${MI_TOTAL}  (validated: ${GREEN}${MI_VALID}${NC}  rejected: ${RED}${MI_REJECTED}${NC}  errors: ${YELLOW}${MI_ERROR}${NC})"

if (( MI_TOTAL >= TOTAL )); then
  echo -e "  Status:     ${GREEN}ALL MESSAGES PROCESSED${NC}"
elif (( MI_TOTAL > 0 )); then
  LOSS=$((TOTAL - MI_TOTAL))
  echo -e "  Status:     ${YELLOW}${LOSS} messages pending${NC}"
else
  echo -e "  Status:     ${RED}NO MESSAGES PROCESSED${NC}"
fi
echo "  ─────────────────────────────────────────────────────────────────────────────────────"
echo ""
