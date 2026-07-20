#!/usr/bin/env bash
# Test: Sustained rate — publish at fixed rates and measure if MI keeps up
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
source "${SCRIPT_DIR}/lib/common.sh"
load_env
check_mi_health
ensure_results_dir
trap cleanup_background EXIT

RATES=(100 500 1000)
DURATION=30
PAYLOAD_FILE="${ROOT_DIR}/samples/json/acme-pub-files.valid.json"
PUB_TOPIC="Acme/SA/PMNO-001/DATASCIENCE/JSON-v1/FromEqt/PUB_FILES"
SCHEMA_PROPS="-cpl=String,schema_group,manufacturing-iot,String,schema_name,pub-files,String,schema_version,1.0.0"

header "Sustained Rate Test — ${DURATION}s per rate"
info "Payload: acme-pub-files.valid.json ($(wc -c < "${PAYLOAD_FILE}" | tr -d ' ')B)"
info "Topic:   ${PUB_TOPIC}"
divider

VERDICTS=()

for rate in "${RATES[@]}"; do
  TOTAL=$((rate * DURATION))
  header "Rate: ${rate} msg/sec — ${TOTAL} messages over ${DURATION}s"

  LOG_MARKER=$(utc_now)
  sleep 1

  # Publish at controlled rate
  PUB_LOG="${RESULTS_DIR}/sustained-${rate}-pub.log"
  START_MS=$(now_ms)
  info "Publishing ${TOTAL} messages at ${rate} msg/sec..."

  run_sdkperf \
    -ptl="${PUB_TOPIC}" \
    -pfl="${PAYLOAD_FILE}" \
    ${SCHEMA_PROPS} \
    -mt=persistent \
    -mn="${TOTAL}" \
    -mr="${rate}" \
    -ped=30 > "${PUB_LOG}" 2>&1 || true

  PUB_END_MS=$(now_ms)
  PUB_ELAPSED=$((PUB_END_MS - START_MS))
  info "Publishing finished in $(format_duration ${PUB_ELAPSED})"

  # Wait for MI to finish processing
  TIMEOUT=$((DURATION + 90))
  info "Waiting for MI to process all messages (timeout ${TIMEOUT}s)..."
  wait_for_mi_processed "${TOTAL}" "${TIMEOUT}" || true

  MI_END_MS=$(now_ms)
  TOTAL_ELAPSED=$((MI_END_MS - START_MS))
  MI_LAG=$((MI_END_MS - PUB_END_MS))

  # MI counters
  snapshot_mi_counters AFTER
  DELTA_VALID=${AFTER_VALID}
  DELTA_INVALID=${AFTER_INVALID}
  DELTA_PROC_ERR=${AFTER_PROC_ERR}
  DELTA_TOTAL=$((DELTA_VALID + DELTA_INVALID + DELTA_PROC_ERR))

  # Verdict
  if (( MI_LAG < 10000 )); then
    VERDICT="${GREEN}PASS${NC}"
    VERDICT_TEXT="PASS"
  elif (( MI_LAG < 30000 )); then
    VERDICT="${YELLOW}WARNING${NC}"
    VERDICT_TEXT="WARNING"
  else
    VERDICT="${RED}FAIL${NC}"
    VERDICT_TEXT="FAIL"
  fi
  VERDICTS+=("${rate}:${VERDICT_TEXT}")

  divider
  echo -e "${BOLD}Results: ${rate} msg/sec${NC}" >&2
  print_result_row "Target rate" "${rate}" "msg/sec"
  print_result_row "Total messages" "${TOTAL}"
  print_result_row "Publish duration" "$(format_duration ${PUB_ELAPSED})"
  print_result_row "End-to-end time" "$(format_duration ${TOTAL_ELAPSED})"
  print_result_row "MI processing lag" "$(format_duration ${MI_LAG})"
  print_result_row "MI total processed" "${DELTA_TOTAL}" "msgs"
  echo -e "  Verdict:                     ${VERDICT}" >&2
  info "MI counters: valid=${DELTA_VALID} invalid=${DELTA_INVALID} proc_err=${DELTA_PROC_ERR}"
  divider
done

header "Summary"
for v in "${VERDICTS[@]}"; do
  IFS=: read -r r verdict <<< "${v}"
  if [[ "${verdict}" == "PASS" ]]; then
    echo -e "  ${r} msg/sec:  ${GREEN}${verdict}${NC}" >&2
  elif [[ "${verdict}" == "WARNING" ]]; then
    echo -e "  ${r} msg/sec:  ${YELLOW}${verdict}${NC}" >&2
  else
    echo -e "  ${r} msg/sec:  ${RED}${verdict}${NC}" >&2
  fi
done

success "Sustained rate test complete. Logs: ${RESULTS_DIR}"
