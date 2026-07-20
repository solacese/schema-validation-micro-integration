#!/usr/bin/env bash
# Test: HA failover — kill one instance mid-flight, verify zero message loss
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
source "${SCRIPT_DIR}/lib/common.sh"
load_env
check_ha_health
ensure_results_dir
trap cleanup_background EXIT

TOTAL=30000
PER_PUB=10000
PUBLISHERS=3
KILL_TARGET="mi-2"
KILL_PORT="8089"
KILL_DELAY=8  # seconds after publish starts

PUB_FILES_PAYLOAD="${ROOT_DIR}/samples/json/acme-pub-files.valid.json"
PUB_TABLE_PAYLOAD="${ROOT_DIR}/samples/json/acme-pub-table.valid.json"
PUB_FILES_TOPIC="Acme/SA/PMNO-001/DATASCIENCE/JSON-v1/FromEqt/PUB_FILES"
PUB_TABLE_TOPIC="Acme/SB/PMNO-042/DATASCIENCE/JSON-v1/FromEqt/PUB_TABLE"
SCHEMA_PUB_FILES="-cpl=String,schema_group,manufacturing-iot,String,schema_name,pub-files,String,schema_version,1.0.0"
SCHEMA_PUB_TABLE="-cpl=String,schema_group,manufacturing-iot,String,schema_name,pub-table,String,schema_version,1.0.0"

header "HA Failover Test — Kill ${KILL_TARGET} mid-flight"
info "Total: ${TOTAL} msgs, kill ${KILL_TARGET} after ${KILL_DELAY}s"
divider

# ─── Phase 1: Start load then kill one instance ───
header "Phase 1: Load + Kill"

LOG_MARKER=$(utc_now)
sleep 1
START_MS=$(now_ms)

# Launch publishers
PUB_PIDS=()
for ((p=1; p<=PUBLISHERS; p++)); do
  PUB_LOG="${RESULTS_DIR}/ha-failover-pub${p}.log"
  if (( p % 2 == 1 )); then
    PID=$(run_sdkperf_bg "${PUB_LOG}" -ptl="${PUB_FILES_TOPIC}" -pfl="${PUB_FILES_PAYLOAD}" ${SCHEMA_PUB_FILES} -mt=persistent -mn="${PER_PUB}" -mr=0 -ped=10)
  else
    PID=$(run_sdkperf_bg "${PUB_LOG}" -ptl="${PUB_TABLE_TOPIC}" -pfl="${PUB_TABLE_PAYLOAD}" ${SCHEMA_PUB_TABLE} -mt=persistent -mn="${PER_PUB}" -mr=0 -ped=10)
  fi
  register_bg_pid "${PID}"
  PUB_PIDS+=("${PID}")
done
info "Publishers started..."

# Wait then kill
info "Waiting ${KILL_DELAY}s before killing ${KILL_TARGET}..."
sleep "${KILL_DELAY}"

# Snapshot before kill
PRE_KILL_TOTAL=$(get_ha_total_since "${LOG_MARKER}")
PRE_KILL_TARGET=$(docker logs --since "${LOG_MARKER}" "${KILL_TARGET}" 2>&1 | grep -c "event=" || true)
info "${KILL_TARGET} processed ${PRE_KILL_TARGET} msgs before kill (cluster total: ${PRE_KILL_TOTAL})"

# Kill
info "Stopping ${KILL_TARGET}..."
docker stop "${KILL_TARGET}" >/dev/null 2>&1
KILL_MS=$(now_ms)
warn "${KILL_TARGET} stopped at +$(( (KILL_MS - START_MS) / 1000 ))s"

# Wait for publishers to finish
for pid in "${PUB_PIDS[@]}"; do wait_for_pid "${pid}" 120 || true; done
PUB_END_MS=$(now_ms)
info "Publishers done in $(format_duration $((PUB_END_MS - START_MS)))"

# ─── Phase 2: Wait for survivors to drain ───
header "Phase 2: Drain with 2 instances"

info "Waiting for surviving instances to process remaining messages..."
# Can only count from mi-1 and mi-3 now (mi-2 is stopped)
DRAIN_TIMEOUT=120
elapsed=0
while (( elapsed < DRAIN_TIMEOUT )); do
  # Count from all 3 (mi-2 logs still accessible even if stopped)
  current=$(get_ha_total_since "${LOG_MARKER}")
  if (( current >= TOTAL )); then
    break
  fi
  if (( elapsed % 10 == 0 )); then
    info "Processed: ${current}/${TOTAL}..."
  fi
  sleep 2
  elapsed=$((elapsed + 2))
done

DRAIN_END_MS=$(now_ms)

# ─── Phase 3: Restart killed instance ───
header "Phase 3: Restart ${KILL_TARGET}"

info "Starting ${KILL_TARGET} back up..."
docker start "${KILL_TARGET}" >/dev/null 2>&1

info "Waiting for ${KILL_TARGET} to become healthy..."
if wait_for_container_healthy "${KILL_TARGET}" "${KILL_PORT}" 60; then
  success "${KILL_TARGET} is back UP"
  # Send a few messages to verify it's processing again
  VERIFY_MARKER=$(utc_now)
  sleep 1
  run_sdkperf \
    -ptl="${PUB_FILES_TOPIC}" -pfl="${PUB_FILES_PAYLOAD}" \
    ${SCHEMA_PUB_FILES} \
    -mt=persistent -mn=100 -mr=0 -ped=5 > "${RESULTS_DIR}/ha-failover-verify.log" 2>&1 || true
  sleep 3
  VERIFY_COUNT=$(docker logs --since "${VERIFY_MARKER}" "${KILL_TARGET}" 2>&1 | grep -c "event=" || true)
  info "${KILL_TARGET} processed ${VERIFY_COUNT}/100 verify messages after restart"
  REJOIN_OK=true
  (( VERIFY_COUNT == 0 )) && REJOIN_OK=false
else
  warn "${KILL_TARGET} did not come back healthy within 60s"
  REJOIN_OK=false
fi

# ─── Results ───
END_MS=$(now_ms)
TOTAL_ELAPSED=$((END_MS - START_MS))

# Per-instance breakdown (from original load, not verify)
divider
echo -e "${BOLD}  Per-Instance Breakdown (main load)${NC}" >&2
printf "  %-10s %10s %10s %10s %10s\n" "Instance" "Total" "Valid" "Reject" "Error" >&2
printf "  %-10s %10s %10s %10s %10s\n" "--------" "-----" "-----" "------" "-----" >&2

GRAND_TOTAL=0
while IFS=: read -r container total valid reject errors; do
  local_label="${container}"
  if [[ "${container}" == "${KILL_TARGET}" ]]; then
    local_label="${container} (killed)"
  fi
  printf "  %-10s %10s %10s %10s %10s\n" "${local_label}" "${total}" "${valid}" "${reject}" "${errors}" >&2
  GRAND_TOTAL=$((GRAND_TOTAL + total))
done < <(get_ha_per_instance_totals "${LOG_MARKER}")

DROPPED=$((TOTAL - GRAND_TOTAL))

divider
header "Failover Results"
print_result_row "Total published" "${TOTAL}" "msgs"
print_result_row "Cluster processed" "${GRAND_TOTAL}" "msgs"
print_result_row "Messages lost" "${DROPPED}" "msgs"
print_result_row "Pre-kill ${KILL_TARGET}" "${PRE_KILL_TARGET}" "msgs"
print_result_row "Kill time" "+$(( (KILL_MS - START_MS) / 1000 ))s"
print_result_row "Drain time" "$(format_duration $((DRAIN_END_MS - KILL_MS)))"
print_result_row "Total time" "$(format_duration ${TOTAL_ELAPSED})"
divider

echo -e "${BOLD}  Verdict${NC}" >&2
print_pass_fail "Zero message loss" "$([ ${DROPPED} -le 0 ] && echo true || echo false)"
print_pass_fail "Survivors absorbed load" "$([ ${GRAND_TOTAL} -ge ${TOTAL} ] && echo true || echo false)"
print_pass_fail "${KILL_TARGET} rejoined" "${REJOIN_OK}"
divider

success "HA failover test complete. Logs: ${RESULTS_DIR}"
