#!/usr/bin/env bash
# Test: Burst throughput — 1k and 5k messages at max publish rate
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
source "${SCRIPT_DIR}/lib/common.sh"
load_env
check_mi_health
ensure_results_dir
trap cleanup_background EXIT

BATCH_SIZES=(1000 5000)
PAYLOAD_FILE="${ROOT_DIR}/samples/json/acme-pub-files.valid.json"
PUB_TOPIC="Acme/SA/PMNO-001/DATASCIENCE/JSON-v1/FromEqt/PUB_FILES"
SCHEMA_PROPS="-cpl=String,schema_group,manufacturing-iot,String,schema_name,pub-files,String,schema_version,1.0.0"

header "Throughput Test — Burst at Max Rate"
info "Payload: acme-pub-files.valid.json ($(wc -c < "${PAYLOAD_FILE}" | tr -d ' ')B)"
info "Topic:   ${PUB_TOPIC}"
divider

for batch in "${BATCH_SIZES[@]}"; do
  header "Batch: ${batch} messages"

  LOG_MARKER=$(utc_now)
  sleep 1

  # Publish burst
  PUB_LOG="${RESULTS_DIR}/throughput-${batch}-pub.log"
  START_MS=$(now_ms)
  info "Publishing ${batch} persistent messages at max rate..."

  run_sdkperf \
    -ptl="${PUB_TOPIC}" \
    -pfl="${PAYLOAD_FILE}" \
    ${SCHEMA_PROPS} \
    -mt=persistent \
    -mn="${batch}" \
    -mr=0 \
    -ped=5 > "${PUB_LOG}" 2>&1 || true

  PUB_END_MS=$(now_ms)
  PUB_ELAPSED=$((PUB_END_MS - START_MS))
  info "Publishing complete in $(format_duration ${PUB_ELAPSED})"

  # Wait for MI to process all messages
  info "Waiting for MI to process ${batch} messages..."
  wait_for_mi_processed "${batch}" 120 || true

  END_MS=$(now_ms)
  TOTAL_ELAPSED=$((END_MS - START_MS))

  # MI counters from Docker logs
  snapshot_mi_counters AFTER
  DELTA_VALID=${AFTER_VALID}
  DELTA_INVALID=${AFTER_INVALID}
  DELTA_PROC_ERR=${AFTER_PROC_ERR}
  DELTA_TOTAL=$((DELTA_VALID + DELTA_INVALID + DELTA_PROC_ERR))

  # Calculate throughput
  if (( TOTAL_ELAPSED > 0 )); then
    THROUGHPUT=$(( batch * 1000 / TOTAL_ELAPSED ))
  else
    THROUGHPUT=0
  fi

  divider
  echo -e "${BOLD}Results: ${batch} messages${NC}" >&2
  print_result_row "Publish time" "$(format_duration ${PUB_ELAPSED})"
  print_result_row "End-to-end time" "$(format_duration ${TOTAL_ELAPSED})"
  print_result_row "Throughput" "${THROUGHPUT}" "msg/sec"
  print_result_row "MI validated" "${DELTA_VALID}" "msgs"
  print_result_row "MI rejected" "${DELTA_INVALID}" "msgs"
  print_result_row "MI errors" "${DELTA_PROC_ERR}" "msgs"
  print_result_row "MI total processed" "${DELTA_TOTAL}" "msgs"
  divider
done

success "Throughput test complete. Logs: ${RESULTS_DIR}"
