#!/usr/bin/env bash
# Test: HA load distribution — verify all instances receive messages
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
source "${SCRIPT_DIR}/lib/common.sh"
load_env
check_ha_health
ensure_results_dir
trap cleanup_background EXIT

TOTAL=30000
PUBLISHERS=3
PER_PUB=$((TOTAL / PUBLISHERS))
PUB_FILES_PAYLOAD="${ROOT_DIR}/samples/json/acme-pub-files.valid.json"
PUB_TABLE_PAYLOAD="${ROOT_DIR}/samples/json/acme-pub-table.valid.json"
PUB_FILES_TOPIC="Acme/SA/PMNO-001/DATASCIENCE/JSON-v1/FromEqt/PUB_FILES"
PUB_TABLE_TOPIC="Acme/SB/PMNO-042/DATASCIENCE/JSON-v1/FromEqt/PUB_TABLE"
SCHEMA_PUB_FILES="-cpl=String,schema_group,manufacturing-iot,String,schema_name,pub-files,String,schema_version,1.0.0"
SCHEMA_PUB_TABLE="-cpl=String,schema_group,manufacturing-iot,String,schema_name,pub-table,String,schema_version,1.0.0"

header "HA Distribution Test — ${TOTAL} messages across 3 instances"
info "Publishers: ${PUBLISHERS} parallel, ${PER_PUB} msgs each"
divider

LOG_MARKER=$(utc_now)
sleep 1
START_MS=$(now_ms)

# Launch publishers
PUB_PIDS=()
for ((p=1; p<=PUBLISHERS; p++)); do
  PUB_LOG="${RESULTS_DIR}/ha-dist-pub${p}.log"
  if (( p % 2 == 1 )); then
    PID=$(run_sdkperf_bg "${PUB_LOG}" -ptl="${PUB_FILES_TOPIC}" -pfl="${PUB_FILES_PAYLOAD}" ${SCHEMA_PUB_FILES} -mt=persistent -mn="${PER_PUB}" -mr=0 -ped=5)
  else
    PID=$(run_sdkperf_bg "${PUB_LOG}" -ptl="${PUB_TABLE_TOPIC}" -pfl="${PUB_TABLE_PAYLOAD}" ${SCHEMA_PUB_TABLE} -mt=persistent -mn="${PER_PUB}" -mr=0 -ped=5)
  fi
  register_bg_pid "${PID}"
  PUB_PIDS+=("${PID}")
done
info "Launched ${PUBLISHERS} publishers..."

for pid in "${PUB_PIDS[@]}"; do wait_for_pid "${pid}" 120 || true; done
PUB_END_MS=$(now_ms)
info "Publishers done in $(format_duration $((PUB_END_MS - START_MS)))"

# Wait for HA cluster to process
info "Waiting for HA cluster to process ${TOTAL} messages..."
wait_for_ha_processed "${TOTAL}" 120 || true

END_MS=$(now_ms)
TOTAL_ELAPSED=$((END_MS - START_MS))

# Per-instance breakdown
divider
echo -e "${BOLD}  Per-Instance Distribution${NC}" >&2
printf "  %-10s %10s %10s %10s %10s\n" "Instance" "Total" "Valid" "Reject" "Error" >&2
printf "  %-10s %10s %10s %10s %10s\n" "--------" "-----" "-----" "------" "-----" >&2

GRAND_TOTAL=0
MIN_SHARE=999999
MAX_SHARE=0
ALL_GOT_MSGS=true

while IFS=: read -r container total valid reject errors; do
  printf "  %-10s %10s %10s %10s %10s\n" "${container}" "${total}" "${valid}" "${reject}" "${errors}" >&2
  GRAND_TOTAL=$((GRAND_TOTAL + total))
  if (( total < MIN_SHARE )); then MIN_SHARE=${total}; fi
  if (( total > MAX_SHARE )); then MAX_SHARE=${total}; fi
  if (( total == 0 )); then ALL_GOT_MSGS=false; fi
done < <(get_ha_per_instance_totals "${LOG_MARKER}")

# Balance metric
if (( MAX_SHARE > 0 )); then
  BALANCE=$((MIN_SHARE * 100 / MAX_SHARE))
else
  BALANCE=0
fi

E2E_THROUGHPUT=0
if (( TOTAL_ELAPSED > 0 )); then
  E2E_THROUGHPUT=$(( GRAND_TOTAL * 1000 / TOTAL_ELAPSED ))
fi

divider
echo -e "${BOLD}  Summary${NC}" >&2
print_result_row "Total published" "${TOTAL}" "msgs"
print_result_row "HA cluster processed" "${GRAND_TOTAL}" "msgs"
print_result_row "End-to-end time" "$(format_duration ${TOTAL_ELAPSED})"
print_result_row "E2E throughput" "${E2E_THROUGHPUT}" "msg/sec"
print_result_row "Min instance share" "${MIN_SHARE}" "msgs"
print_result_row "Max instance share" "${MAX_SHARE}" "msgs"
print_result_row "Balance (min/max)" "${BALANCE}%"
divider

echo -e "${BOLD}  Verdict${NC}" >&2
print_pass_fail "All messages processed" "$([ ${GRAND_TOTAL} -ge ${TOTAL} ] && echo true || echo false)"
print_pass_fail "All instances active" "${ALL_GOT_MSGS}"
print_pass_fail "Balance > 20%" "$([ ${BALANCE} -ge 20 ] && echo true || echo false)"
divider

success "HA distribution test complete. Logs: ${RESULTS_DIR}"
