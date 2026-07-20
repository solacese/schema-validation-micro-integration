#!/usr/bin/env bash
# Test: Backpressure — flood MI queue faster than it can consume,
# then measure drain rate and message loss
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
ROOT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
source "${SCRIPT_DIR}/lib/common.sh"
load_env
check_mi_health
ensure_results_dir
trap cleanup_background EXIT

# Phase 1: Pre-flood with direct messages to fill the queue instantly
# Phase 2: Measure how fast MI drains
# Phase 3: Flood again while MI is draining

FLOOD_MSGS=50000
PUBLISHERS=6
PER_PUB=$((FLOOD_MSGS / PUBLISHERS))
PUB_FILES_PAYLOAD="${ROOT_DIR}/samples/json/acme-pub-files.valid.json"
PUB_TABLE_PAYLOAD="${ROOT_DIR}/samples/json/acme-pub-table.valid.json"
PUB_FILES_TOPIC="Acme/SA/PMNO-001/DATASCIENCE/JSON-v1/FromEqt/PUB_FILES"
PUB_TABLE_TOPIC="Acme/SB/PMNO-042/DATASCIENCE/JSON-v1/FromEqt/PUB_TABLE"
SCHEMA_PUB_FILES="-cpl=String,schema_group,manufacturing-iot,String,schema_name,pub-files,String,schema_version,1.0.0"
SCHEMA_PUB_TABLE="-cpl=String,schema_group,manufacturing-iot,String,schema_name,pub-table,String,schema_version,1.0.0"

header "Backpressure Test — ${FLOOD_MSGS} messages, ${PUBLISHERS} publishers"
info "Phase 1: Flood the queue as fast as possible"
info "Phase 2: Measure MI drain rate under pressure"
divider

# ─── Phase 1: Flood ───
header "Phase 1: Flood"
LOG_MARKER=$(utc_now)
sleep 1
FLOOD_START=$(now_ms)

PUB_PIDS=()
for ((p=1; p<=PUBLISHERS; p++)); do
  PUB_LOG="${RESULTS_DIR}/backpressure-flood-p${p}.log"
  if (( p % 2 == 1 )); then
    PID=$(run_sdkperf_bg "${PUB_LOG}" \
      -ptl="${PUB_FILES_TOPIC}" \
      -pfl="${PUB_FILES_PAYLOAD}" \
      ${SCHEMA_PUB_FILES} \
      -mt=persistent \
      -mn="${PER_PUB}" \
      -mr=0 \
      -ped=10)
  else
    PID=$(run_sdkperf_bg "${PUB_LOG}" \
      -ptl="${PUB_TABLE_TOPIC}" \
      -pfl="${PUB_TABLE_PAYLOAD}" \
      ${SCHEMA_PUB_TABLE} \
      -mt=persistent \
      -mn="${PER_PUB}" \
      -mr=0 \
      -ped=10)
  fi
  register_bg_pid "${PID}"
  PUB_PIDS+=("${PID}")
done

info "Launched ${PUBLISHERS} publishers flooding ${FLOOD_MSGS} messages..."

# Wait for publishers
for pid in "${PUB_PIDS[@]}"; do
  wait_for_pid "${pid}" 300 || warn "Publisher ${pid} timed out"
done

FLOOD_END=$(now_ms)
FLOOD_ELAPSED=$((FLOOD_END - FLOOD_START))

# Get actual published count + aggregate rate
TOTAL_PUBLISHED=0
AGG_PUB_RATE=0
TOTAL_NACKS=0
for ((p=1; p<=PUBLISHERS; p++)); do
  PUB_LOG="${RESULTS_DIR}/backpressure-flood-p${p}.log"
  tx=$(grep "Total Messages transmitted" "${PUB_LOG}" 2>/dev/null | awk '{print $NF}' || echo "0")
  rate=$(grep "Computed publish rate" "${PUB_LOG}" 2>/dev/null | sed 's/.*= //' | sed 's/\..*//' | tr -d ' ' || echo "0")
  nack=$(grep "Total NACK Events" "${PUB_LOG}" 2>/dev/null | awk '{print $NF}' || echo "0")
  TOTAL_PUBLISHED=$((TOTAL_PUBLISHED + tx))
  [[ "${rate}" =~ ^[0-9]+$ ]] && AGG_PUB_RATE=$((AGG_PUB_RATE + rate))
  TOTAL_NACKS=$((TOTAL_NACKS + nack))
done

# How much did MI process during flooding?
MI_DURING_FLOOD=$(get_mi_total_since "${LOG_MARKER}")
QUEUE_DEPTH=$((TOTAL_PUBLISHED - MI_DURING_FLOOD))

info "Flood complete in $(format_duration ${FLOOD_ELAPSED})"
info "Published:        ${TOTAL_PUBLISHED} msgs at ~${AGG_PUB_RATE} msg/sec aggregate"
info "MI processed:     ${MI_DURING_FLOOD} during flood"
info "Estimated queue:  ~${QUEUE_DEPTH} msgs pending"
if (( TOTAL_NACKS > 0 )); then
  warn "Broker NACKs: ${TOTAL_NACKS}"
fi

# ─── Phase 2: Drain ───
header "Phase 2: Drain"
info "Measuring MI drain rate..."

DRAIN_START=$(now_ms)
PREV_TOTAL=${MI_DURING_FLOOD}
PREV_MS=${DRAIN_START}
DRAIN_SAMPLES=()
MAX_DRAIN=0

for ((s=1; s<=60; s++)); do
  sleep 5
  NOW_MS=$(now_ms)
  CURRENT=$(get_mi_total_since "${LOG_MARKER}")
  DELTA=$((CURRENT - PREV_TOTAL))
  INTERVAL=$((NOW_MS - PREV_MS))
  if (( INTERVAL > 0 )); then
    RATE=$(( DELTA * 1000 / INTERVAL ))
  else
    RATE=0
  fi

  REMAINING=$((TOTAL_PUBLISHED - CURRENT))
  printf "  +%3ds  processed: %7d  delta: %5d  rate: %5d msg/s  remaining: %6d\n" \
    "$((s * 5))" "${CURRENT}" "${DELTA}" "${RATE}" "${REMAINING}" >&2

  DRAIN_SAMPLES+=("${RATE}")
  if (( RATE > MAX_DRAIN )); then MAX_DRAIN=${RATE}; fi

  PREV_TOTAL=${CURRENT}
  PREV_MS=${NOW_MS}

  # Stop if fully drained
  if (( CURRENT >= TOTAL_PUBLISHED )); then
    info "Queue fully drained"
    break
  fi

  # Stop if MI has stalled (3 consecutive zero-rate samples)
  if (( s >= 3 )); then
    last3=0
    for ((i=${#DRAIN_SAMPLES[@]}-3; i<${#DRAIN_SAMPLES[@]}; i++)); do
      last3=$((last3 + DRAIN_SAMPLES[i]))
    done
    if (( last3 == 0 )); then
      warn "MI appears stalled — 3 consecutive zero-rate samples"
      break
    fi
  fi
done

DRAIN_END=$(now_ms)
DRAIN_ELAPSED=$((DRAIN_END - DRAIN_START))
TOTAL_ELAPSED=$((DRAIN_END - FLOOD_START))

# Final counts
DELTA_VALID=$(count_mi_events "${LOG_MARKER}" validated)
DELTA_INVALID=$(count_mi_events "${LOG_MARKER}" rejected)
DELTA_PROC_ERR=$(count_mi_events "${LOG_MARKER}" processing_error)
DELTA_TOTAL=$((DELTA_VALID + DELTA_INVALID + DELTA_PROC_ERR))
DROPPED=$((TOTAL_PUBLISHED - DELTA_TOTAL))

# Avg drain rate (excluding flood phase)
if (( DRAIN_ELAPSED > 0 && DELTA_TOTAL > MI_DURING_FLOOD )); then
  AVG_DRAIN_RATE=$(( (DELTA_TOTAL - MI_DURING_FLOOD) * 1000 / DRAIN_ELAPSED ))
else
  AVG_DRAIN_RATE=0
fi

# Overall E2E
if (( TOTAL_ELAPSED > 0 )); then
  E2E_RATE=$(( DELTA_TOTAL * 1000 / TOTAL_ELAPSED ))
else
  E2E_RATE=0
fi

divider
header "Backpressure Results"
echo "" >&2
echo -e "${BOLD}  Flood Phase${NC}" >&2
print_result_row "Publishers" "${PUBLISHERS}"
print_result_row "Total published" "${TOTAL_PUBLISHED}" "msgs"
print_result_row "Aggregate pub rate" "${AGG_PUB_RATE}" "msg/sec"
print_result_row "Flood duration" "$(format_duration ${FLOOD_ELAPSED})"
print_result_row "Broker NACKs" "${TOTAL_NACKS}"
divider

echo -e "${BOLD}  Drain Phase${NC}" >&2
print_result_row "Drain duration" "$(format_duration ${DRAIN_ELAPSED})"
print_result_row "Peak drain rate" "${MAX_DRAIN}" "msg/sec"
print_result_row "Avg drain rate" "${AVG_DRAIN_RATE}" "msg/sec"
divider

echo -e "${BOLD}  Overall${NC}" >&2
print_result_row "Total time" "$(format_duration ${TOTAL_ELAPSED})"
print_result_row "E2E throughput" "${E2E_RATE}" "msg/sec"
print_result_row "MI validated" "${DELTA_VALID}" "msgs"
print_result_row "MI rejected" "${DELTA_INVALID}" "msgs"
print_result_row "MI errors" "${DELTA_PROC_ERR}" "msgs"
print_result_row "MI total processed" "${DELTA_TOTAL}" "msgs"
print_result_row "Dropped/lost" "${DROPPED}" "msgs"
divider

echo -e "${BOLD}  Verdict${NC}" >&2
print_pass_fail "Zero message loss" "$([ ${DROPPED} -le 0 ] && echo true || echo false)"
# If MI drained everything during flood, that's better than 1k/s drain
if (( QUEUE_DEPTH <= 0 )); then
  print_pass_fail "Drain rate > 1k msg/s" "true"
  info "(MI kept up in real time — no backlog to drain)"
else
  print_pass_fail "Drain rate > 1k msg/s" "$([ ${AVG_DRAIN_RATE} -ge 1000 ] && echo true || echo false)"
fi
print_pass_fail "Zero NACKs" "$([ ${TOTAL_NACKS} -eq 0 ] && echo true || echo false)"
divider

success "Backpressure test complete. Logs: ${RESULTS_DIR}"
