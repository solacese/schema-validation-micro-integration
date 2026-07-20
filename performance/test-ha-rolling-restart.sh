#!/usr/bin/env bash
# Test: HA rolling restart — restart each instance one-by-one during sustained load
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
source "${SCRIPT_DIR}/lib/common.sh"
load_env
check_ha_health
ensure_results_dir
trap cleanup_background EXIT

TOTAL=60000
PER_PUB=20000
PUBLISHERS=3
PUB_FILES_PAYLOAD="${ROOT_DIR}/samples/json/acme-pub-files.valid.json"
PUB_TABLE_PAYLOAD="${ROOT_DIR}/samples/json/acme-pub-table.valid.json"
PUB_FILES_TOPIC="Acme/SA/PMNO-001/DATASCIENCE/JSON-v1/FromEqt/PUB_FILES"
PUB_TABLE_TOPIC="Acme/SB/PMNO-042/DATASCIENCE/JSON-v1/FromEqt/PUB_TABLE"
SCHEMA_PUB_FILES="-cpl=String,schema_group,manufacturing-iot,String,schema_name,pub-files,String,schema_version,1.0.0"
SCHEMA_PUB_TABLE="-cpl=String,schema_group,manufacturing-iot,String,schema_name,pub-table,String,schema_version,1.0.0"

header "HA Rolling Restart — restart each instance during load"
info "Total: ${TOTAL} msgs from ${PUBLISHERS} publishers"
info "Sequence: restart mi-1, wait healthy, mi-2, wait healthy, mi-3, wait healthy"
divider

LOG_MARKER=$(utc_now)
sleep 1
START_MS=$(now_ms)

# Launch publishers in background — they run for a while (~30-40s)
PUB_PIDS=()
for ((p=1; p<=PUBLISHERS; p++)); do
  PUB_LOG="${RESULTS_DIR}/ha-rolling-pub${p}.log"
  if (( p % 2 == 1 )); then
    PID=$(run_sdkperf_bg "${PUB_LOG}" -ptl="${PUB_FILES_TOPIC}" -pfl="${PUB_FILES_PAYLOAD}" ${SCHEMA_PUB_FILES} -mt=persistent -mn="${PER_PUB}" -mr=500 -ped=10)
  else
    PID=$(run_sdkperf_bg "${PUB_LOG}" -ptl="${PUB_TABLE_TOPIC}" -pfl="${PUB_TABLE_PAYLOAD}" ${SCHEMA_PUB_TABLE} -mt=persistent -mn="${PER_PUB}" -mr=500 -ped=10)
  fi
  register_bg_pid "${PID}"
  PUB_PIDS+=("${PID}")
done
info "Publishers started at 500 msg/sec each (~1500 aggregate)"

# Give publishers time to ramp up
sleep 5
PHASE_TOTAL=$(get_ha_total_since "${LOG_MARKER}")
info "Pre-restart: ${PHASE_TOTAL} processed so far"

# Rolling restart each instance
RESTART_LOG=()
for i in "${!HA_CONTAINERS[@]}"; do
  local_container="${HA_CONTAINERS[$i]}"
  local_port="${HA_PORTS[$i]}"

  divider
  PRE=$(get_ha_total_since "${LOG_MARKER}")
  info "Restarting ${local_container} (processed so far: ${PRE})..."

  RESTART_START=$(now_ms)
  docker restart "${local_container}" >/dev/null 2>&1

  # Wait for it to come back
  if wait_for_container_healthy "${local_container}" "${local_port}" 60; then
    RESTART_END=$(now_ms)
    RESTART_DUR=$(( RESTART_END - RESTART_START ))
    success "${local_container} back healthy in $(format_duration ${RESTART_DUR})"
    RESTART_LOG+=("${local_container}|$(format_duration ${RESTART_DUR})|OK")
  else
    RESTART_END=$(now_ms)
    RESTART_DUR=$(( RESTART_END - RESTART_START ))
    warn "${local_container} did not recover within 60s"
    RESTART_LOG+=("${local_container}|$(format_duration ${RESTART_DUR})|TIMEOUT")
  fi

  POST=$(get_ha_total_since "${LOG_MARKER}")
  DURING=$((POST - PRE))
  info "Processed during ${local_container} restart: ${DURING} msgs"

  # Small gap between restarts to let cluster stabilize
  sleep 3
done

# Wait for all publishers to finish
info "Waiting for publishers to finish..."
for pid in "${PUB_PIDS[@]}"; do wait_for_pid "${pid}" 180 || true; done
PUB_END_MS=$(now_ms)
info "Publishers done in $(format_duration $((PUB_END_MS - START_MS)))"

# Wait for cluster to drain
info "Waiting for cluster to drain..."
wait_for_ha_processed "${TOTAL}" 120 || true

END_MS=$(now_ms)
TOTAL_ELAPSED=$((END_MS - START_MS))

# Results
divider
echo -e "${BOLD}  Restart Timings${NC}" >&2
printf "  %-10s %12s %8s\n" "Instance" "Downtime" "Status" >&2
printf "  %-10s %12s %8s\n" "--------" "--------" "------" >&2
for entry in "${RESTART_LOG[@]}"; do
  IFS='|' read -r c dur status <<< "${entry}"
  printf "  %-10s %12s %8s\n" "${c}" "${dur}" "${status}" >&2
done

divider
echo -e "${BOLD}  Per-Instance Breakdown${NC}" >&2
printf "  %-10s %10s %10s %10s %10s\n" "Instance" "Total" "Valid" "Reject" "Error" >&2
printf "  %-10s %10s %10s %10s %10s\n" "--------" "-----" "-----" "------" "-----" >&2

GRAND_TOTAL=0
ALL_ACTIVE=true
while IFS=: read -r container total valid reject errors; do
  printf "  %-10s %10s %10s %10s %10s\n" "${container}" "${total}" "${valid}" "${reject}" "${errors}" >&2
  GRAND_TOTAL=$((GRAND_TOTAL + total))
  if (( total == 0 )); then ALL_ACTIVE=false; fi
done < <(get_ha_per_instance_totals "${LOG_MARKER}")

DROPPED=$((TOTAL - GRAND_TOTAL))

E2E_THROUGHPUT=0
if (( TOTAL_ELAPSED > 0 )); then
  E2E_THROUGHPUT=$(( GRAND_TOTAL * 1000 / TOTAL_ELAPSED ))
fi

divider
header "Rolling Restart Results"
print_result_row "Total published" "${TOTAL}" "msgs"
print_result_row "Cluster processed" "${GRAND_TOTAL}" "msgs"
print_result_row "Messages lost" "${DROPPED}" "msgs"
print_result_row "E2E throughput" "${E2E_THROUGHPUT}" "msg/sec"
print_result_row "Total time" "$(format_duration ${TOTAL_ELAPSED})"
divider

echo -e "${BOLD}  Verdict${NC}" >&2
print_pass_fail "Zero message loss" "$([ ${DROPPED} -le 0 ] && echo true || echo false)"
print_pass_fail "All instances restarted OK" "$(echo "${RESTART_LOG[*]}" | grep -qv TIMEOUT && echo true || echo false)"
print_pass_fail "All instances processed msgs" "${ALL_ACTIVE}"
print_pass_fail "Throughput > 500 msg/s" "$([ ${E2E_THROUGHPUT} -ge 500 ] && echo true || echo false)"
divider

success "HA rolling restart test complete. Logs: ${RESULTS_DIR}"
