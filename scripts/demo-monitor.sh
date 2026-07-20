#!/usr/bin/env bash
# Live throughput monitor — prints one line per second with MI processing stats
# Run alongside any load (demo-stress.sh, demo-publish.sh, or external publishers)
#
# Usage:
#   ./scripts/demo-monitor.sh           # Monitor until Ctrl-C
#   ./scripts/demo-monitor.sh 60        # Monitor for 60 seconds
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
MAX_SECONDS="${1:-0}"  # 0 = unlimited

# ── Colors ──
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; BOLD='\033[1m'; DIM='\033[2m'; NC='\033[0m'

# ── Check MI ──
MI_CONTAINER="${MI_CONTAINER:-schema-validation-micro-integration}"
MI_HTTP=$(curl -s -o /dev/null -w "%{http_code}" "http://localhost:8088/actuator/health" 2>/dev/null || echo "000")
if [[ "${MI_HTTP}" == "000" ]]; then
  echo "MI not reachable on localhost:8088 — run ./scripts/demo-setup.sh first"
  exit 1
fi

# ── Capture log marker (count events from now) ──
LOG_MARKER=$(python3 -c "from datetime import datetime,timezone; print(datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'))")
sleep 1

count_mi_events() {
  local count
  count=$(docker logs --since "${LOG_MARKER}" "${MI_CONTAINER}" 2>&1 | grep -c "event=$1" 2>/dev/null) || count=0
  echo "${count}"
}

echo ""
echo -e "${BOLD}  Throughput Monitor${NC}"
echo "  ─────────────────────────────────────────────────────────────────────"
echo -e "  Tracking MI container: ${DIM}${MI_CONTAINER}${NC}"
echo "  Press Ctrl-C to stop."
echo ""

# Header
printf "${BOLD}%8s  %10s  %10s  %10s  %10s  %10s  %8s${NC}\n" \
  "elapsed" "total" "+/sec" "+valid" "+reject" "+error" "avg/s"

PREV_TOTAL=0
PREV_VALID=0
PREV_REJECTED=0
PREV_ERROR=0
START_SEC=$(date +%s)

while true; do
  sleep 1

  NOW_SEC=$(date +%s)
  ELAPSED=$((NOW_SEC - START_SEC))
  MINUTES=$((ELAPSED / 60))
  SECS=$((ELAPSED % 60))
  ELAPSED_FMT=$(printf "%dm%02ds" "${MINUTES}" "${SECS}")

  VALID=$(count_mi_events "validated")
  REJECTED=$(count_mi_events "rejected")
  ERROR=$(count_mi_events "processing_error")
  TOTAL_COUNT=$((VALID + REJECTED + ERROR))

  DELTA_TOTAL=$((TOTAL_COUNT - PREV_TOTAL))
  DELTA_VALID=$((VALID - PREV_VALID))
  DELTA_REJECTED=$((REJECTED - PREV_REJECTED))
  DELTA_ERROR=$((ERROR - PREV_ERROR))

  # Average throughput
  if (( ELAPSED > 0 )); then
    AVG=$((TOTAL_COUNT / ELAPSED))
  else
    AVG=0
  fi

  # Color deltas
  if (( DELTA_TOTAL > 0 )); then
    DELTA_COLOR="${GREEN}+${DELTA_TOTAL}${NC}"
  else
    DELTA_COLOR="${DIM}+0${NC}"
  fi

  if (( DELTA_VALID > 0 )); then
    VALID_COLOR="${GREEN}+${DELTA_VALID}${NC}"
  else
    VALID_COLOR="${DIM}+0${NC}"
  fi

  if (( DELTA_REJECTED > 0 )); then
    REJECT_COLOR="${RED}+${DELTA_REJECTED}${NC}"
  else
    REJECT_COLOR="${DIM}+0${NC}"
  fi

  if (( DELTA_ERROR > 0 )); then
    ERROR_COLOR="${YELLOW}+${DELTA_ERROR}${NC}"
  else
    ERROR_COLOR="${DIM}+0${NC}"
  fi

  # Color avg rate
  if (( AVG >= 1000 )); then
    AVG_COLOR="${GREEN}${AVG}${NC}"
  elif (( AVG > 0 )); then
    AVG_COLOR="${YELLOW}${AVG}${NC}"
  else
    AVG_COLOR="${DIM}0${NC}"
  fi

  printf "%8s  %10s  %10b  %10b  %10b  %10b  %8b\n" \
    "${ELAPSED_FMT}" \
    "${TOTAL_COUNT}" \
    "${DELTA_COLOR}" \
    "${VALID_COLOR}" \
    "${REJECT_COLOR}" \
    "${ERROR_COLOR}" \
    "${AVG_COLOR}"

  PREV_TOTAL=${TOTAL_COUNT}
  PREV_VALID=${VALID}
  PREV_REJECTED=${REJECTED}
  PREV_ERROR=${ERROR}

  # Stop after MAX_SECONDS if set
  if (( MAX_SECONDS > 0 && ELAPSED >= MAX_SECONDS )); then
    break
  fi
done

echo ""
echo "  ─────────────────────────────────────────────────────────────────────"
echo -e "  ${BOLD}Total${NC}: ${TOTAL_COUNT}  (validated: ${GREEN}${VALID}${NC}  rejected: ${RED}${REJECTED}${NC}  errors: ${YELLOW}${ERROR}${NC})  avg: ${AVG} msg/s"
echo "  ─────────────────────────────────────────────────────────────────────"
echo ""
