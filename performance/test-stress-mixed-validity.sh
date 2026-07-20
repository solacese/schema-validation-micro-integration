#!/usr/bin/env bash
# Test: Stress with realistic error ratio — 99% valid, 1% invalid
# 3 parallel publishers, 10k messages each = 30k total
# Each publisher alternates: 99 valid then 1 invalid, repeating
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
source "${SCRIPT_DIR}/lib/common.sh"
load_env
check_mi_health
ensure_results_dir
trap cleanup_background EXIT

PER_PUBLISHER=10000
PUBLISHERS=3
TOTAL=$((PER_PUBLISHER * PUBLISHERS))
VALID_RATIO=99
INVALID_RATIO=1
BATCH=$((VALID_RATIO + INVALID_RATIO))
VALID_PER_PUB=$(( PER_PUBLISHER * VALID_RATIO / BATCH ))
INVALID_PER_PUB=$(( PER_PUBLISHER * INVALID_RATIO / BATCH ))
EXPECTED_VALID=$(( VALID_PER_PUB * PUBLISHERS ))
EXPECTED_INVALID=$(( INVALID_PER_PUB * PUBLISHERS ))

# Build interleaved payload list: 99 valid, 1 invalid, repeated
# SDKPerf -pfl cycles through the file list round-robin
VALID_PUB_FILES="${ROOT_DIR}/samples/json/acme-pub-files.valid.json"
INVALID_PUB_FILES="${ROOT_DIR}/samples/json/acme-pub-files.invalid.json"
VALID_PUB_TABLE="${ROOT_DIR}/samples/json/acme-pub-table.valid.json"
INVALID_PUB_TABLE="${ROOT_DIR}/samples/json/acme-pub-table.invalid.json"

# Build comma-separated file lists for -pfl (99 valid, 1 invalid)
build_pfl_list() {
  local valid="$1" invalid="$2"
  local list=""
  for ((i=0; i<VALID_RATIO; i++)); do
    list+="${valid},"
  done
  list+="${invalid}"
  echo "${list}"
}

PUB_FILES_PFL=$(build_pfl_list "${VALID_PUB_FILES}" "${INVALID_PUB_FILES}")
PUB_TABLE_PFL=$(build_pfl_list "${VALID_PUB_TABLE}" "${INVALID_PUB_TABLE}")

SCHEMA_PUB_FILES="-cpl=String,schema_group,manufacturing-iot,String,schema_name,pub-files,String,schema_version,1.0.0"
SCHEMA_PUB_TABLE="-cpl=String,schema_group,manufacturing-iot,String,schema_name,pub-table,String,schema_version,1.0.0"

header "Stress Test — 99% valid / 1% invalid"
info "Publishers:       ${PUBLISHERS} parallel"
info "Messages/pub:     ${PER_PUBLISHER}"
info "Total messages:   ${TOTAL}"
info "Expected valid:   ${EXPECTED_VALID} (${VALID_RATIO}%)"
info "Expected invalid: ${EXPECTED_INVALID} (${INVALID_RATIO}%)"
info "Payload cycle:    ${VALID_RATIO} valid then ${INVALID_RATIO} invalid"
divider

LOG_MARKER=$(utc_now)
sleep 1
START_MS=$(now_ms)

# Publisher 1: PUB_FILES from SiteA
PUB1_LOG="${RESULTS_DIR}/stress-pub1-files.log"
info "Starting publisher 1 (PUB_FILES → Acme/SA/PMNO-001/DATASCIENCE/...)..."
PUB1_PID=$(run_sdkperf_bg "${PUB1_LOG}" \
  -ptl=Acme/SA/PMNO-001/DATASCIENCE/JSON-v1/FromEqt/PUB_FILES \
  -pfl="${PUB_FILES_PFL}" \
  ${SCHEMA_PUB_FILES} \
  -mt=persistent \
  -mn="${PER_PUBLISHER}" \
  -mr=0 \
  -ped=5)
register_bg_pid "${PUB1_PID}"

# Publisher 2: PUB_TABLE from SiteB
PUB2_LOG="${RESULTS_DIR}/stress-pub2-table.log"
info "Starting publisher 2 (PUB_TABLE → Acme/SB/PMNO-042/DATASCIENCE/...)..."
PUB2_PID=$(run_sdkperf_bg "${PUB2_LOG}" \
  -ptl=Acme/SB/PMNO-042/DATASCIENCE/JSON-v1/FromEqt/PUB_TABLE \
  -pfl="${PUB_TABLE_PFL}" \
  ${SCHEMA_PUB_TABLE} \
  -mt=persistent \
  -mn="${PER_PUBLISHER}" \
  -mr=0 \
  -ped=5)
register_bg_pid "${PUB2_PID}"

# Publisher 3: PUB_FILES from SiteC (SHOPFLOOR)
PUB3_LOG="${RESULTS_DIR}/stress-pub3-shopfloor.log"
info "Starting publisher 3 (PUB_FILES → Acme/SC/PMNO-100/SHOPFLOOR/...)..."
PUB3_PID=$(run_sdkperf_bg "${PUB3_LOG}" \
  -ptl=Acme/SC/PMNO-100/SHOPFLOOR/JSON-v1/FromEqt/PUB_FILES \
  -pfl="${PUB_FILES_PFL}" \
  ${SCHEMA_PUB_FILES} \
  -mt=persistent \
  -mn="${PER_PUBLISHER}" \
  -mr=0 \
  -ped=5)
register_bg_pid "${PUB3_PID}"

# Wait for all publishers
info "Waiting for ${PUBLISHERS} publishers..."
wait_for_pid "${PUB1_PID}" 120 || warn "Publisher 1 timed out"
wait_for_pid "${PUB2_PID}" 120 || warn "Publisher 2 timed out"
wait_for_pid "${PUB3_PID}" 120 || warn "Publisher 3 timed out"

PUB_END_MS=$(now_ms)
PUB_ELAPSED=$((PUB_END_MS - START_MS))
info "All publishers done in $(format_duration ${PUB_ELAPSED})"

# Extract per-publisher rates
extract_pub_rate() { grep "Computed publish rate" "$1" 2>/dev/null | sed 's/.*= //' | tr -d ' ' || echo "?"; }
PUB1_RATE=$(extract_pub_rate "${PUB1_LOG}")
PUB2_RATE=$(extract_pub_rate "${PUB2_LOG}")
PUB3_RATE=$(extract_pub_rate "${PUB3_LOG}")

# Wait for MI to process all
info "Waiting for MI to process ${TOTAL} messages..."
wait_for_mi_processed "${TOTAL}" 120 || true

END_MS=$(now_ms)
TOTAL_ELAPSED=$((END_MS - START_MS))
MI_LAG=$((END_MS - PUB_END_MS))

# MI counters
DELTA_VALID=$(count_mi_events "${LOG_MARKER}" validated)
DELTA_INVALID=$(count_mi_events "${LOG_MARKER}" rejected)
DELTA_PROC_ERR=$(count_mi_events "${LOG_MARKER}" processing_error)
DELTA_TOTAL=$((DELTA_VALID + DELTA_INVALID + DELTA_PROC_ERR))

# Throughput
if (( TOTAL_ELAPSED > 0 )); then
  E2E_THROUGHPUT=$(( TOTAL * 1000 / TOTAL_ELAPSED ))
else
  E2E_THROUGHPUT=0
fi

# Actual ratio
if (( DELTA_TOTAL > 0 )); then
  ACTUAL_VALID_PCT=$(( DELTA_VALID * 100 / DELTA_TOTAL ))
  ACTUAL_INVALID_PCT=$(( DELTA_INVALID * 100 / DELTA_TOTAL ))
else
  ACTUAL_VALID_PCT=0
  ACTUAL_INVALID_PCT=0
fi

# Verify ratios
VALID_OK=false
INVALID_OK=false
# Allow ±1% tolerance
(( ACTUAL_VALID_PCT >= 98 && ACTUAL_VALID_PCT <= 100 )) && VALID_OK=true
(( ACTUAL_INVALID_PCT >= 0 && ACTUAL_INVALID_PCT <= 2 )) && INVALID_OK=true

divider
header "Results"
echo "" >&2
echo -e "${BOLD}  SDKPerf Publish Rates${NC}" >&2
print_result_row "Publisher 1 (PUB_FILES)" "${PUB1_RATE}" "msg/sec"
print_result_row "Publisher 2 (PUB_TABLE)" "${PUB2_RATE}" "msg/sec"
print_result_row "Publisher 3 (SHOPFLOOR)" "${PUB3_RATE}" "msg/sec"
divider

echo -e "${BOLD}  MI Processing${NC}" >&2
print_result_row "Total messages" "${TOTAL}"
print_result_row "Publish time" "$(format_duration ${PUB_ELAPSED})"
print_result_row "End-to-end time" "$(format_duration ${TOTAL_ELAPSED})"
print_result_row "MI drain lag" "$(format_duration ${MI_LAG})"
print_result_row "E2E throughput" "${E2E_THROUGHPUT}" "msg/sec"
divider

echo -e "${BOLD}  Validation Breakdown${NC}" >&2
print_result_row "MI validated" "${DELTA_VALID}" "(expected ~${EXPECTED_VALID})"
print_result_row "MI rejected" "${DELTA_INVALID}" "(expected ~${EXPECTED_INVALID})"
print_result_row "MI errors" "${DELTA_PROC_ERR}"
print_result_row "Total processed" "${DELTA_TOTAL}" "(expected ${TOTAL})"
print_result_row "Valid ratio" "${ACTUAL_VALID_PCT}%" "(expected ${VALID_RATIO}%)"
print_result_row "Invalid ratio" "${ACTUAL_INVALID_PCT}%" "(expected ${INVALID_RATIO}%)"
divider

echo -e "${BOLD}  Verdict${NC}" >&2
print_pass_fail "Throughput > 1000 msg/s" "$([ ${E2E_THROUGHPUT} -ge 1000 ] && echo true || echo false)"
print_pass_fail "All messages processed" "$([ ${DELTA_TOTAL} -ge ${TOTAL} ] && echo true || echo false)"
print_pass_fail "Valid ratio ~99%" "${VALID_OK}"
print_pass_fail "Invalid ratio ~1%" "${INVALID_OK}"
divider

success "Stress test complete. Logs: ${RESULTS_DIR}"
