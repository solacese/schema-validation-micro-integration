#!/usr/bin/env bash
# Shared library for SDKPerf performance tests

# ---------- colors ----------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
BOLD='\033[1m'
DIM='\033[2m'
NC='\033[0m'

info()    { echo -e "${CYAN}[INFO]${NC}  $*" >&2; }
warn()    { echo -e "${YELLOW}[WARN]${NC}  $*" >&2; }
error()   { echo -e "${RED}[ERROR]${NC} $*" >&2; }
success() { echo -e "${GREEN}[OK]${NC}    $*" >&2; }
header()  { echo -e "\n${BOLD}═══ $* ═══${NC}" >&2; }
divider() { echo -e "${DIM}───────────────────────────────────────────────${NC}" >&2; }

# ---------- paths ----------
SCRIPT_DIR="${SCRIPT_DIR:-$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)}"
ROOT_DIR="${ROOT_DIR:-$(cd "${SCRIPT_DIR}/.." && pwd)}"
SDKPERF_CMD="${SDKPERF_CMD:-${ROOT_DIR}/performance/sdkperf-jcsmp-10.29.1/sdkperf_java.sh}"
MI_HOST="${MI_HOST:-localhost}"
MI_PORT="${MI_PORT:-8088}"

# ---------- env loading ----------
load_env() {
  local env_file="${ROOT_DIR}/.env"
  if [[ ! -f "${env_file}" ]]; then
    error "Missing ${env_file} — copy .env.example and fill in broker credentials"
    exit 1
  fi
  set -a
  source "${env_file}"
  set +a
  : "${URL:?Missing URL in .env}"
  : "${VPN:?Missing VPN in .env}"
  : "${USER:?Missing USER in .env}"
  : "${PWD:?Missing PWD in .env}"

  # SDKPerf runs on the host — translate container hostnames to localhost
  # SDKPerf uses JCSMP (SMF), so translate wss:// cloud URLs to tcps:// SMF
  local sdkperf_url="${URL}"
  sdkperf_url="${sdkperf_url//solace-broker/localhost}"
  sdkperf_url="${sdkperf_url//55555/55554}"
  if [[ "${sdkperf_url}" == wss://* ]]; then
    sdkperf_url="${sdkperf_url/wss:\/\//tcps://}"
    sdkperf_url="${sdkperf_url/:443/:55443}"
  fi
  SDKPERF_CONN_ARGS=("-cip=${sdkperf_url}" "-cu=${USER}@${VPN}" "-cp=${PWD}")
  success "Loaded broker (VPN: ${VPN}, sdkperf→${sdkperf_url})"
}

# ---------- sdkperf wrapper ----------
run_sdkperf() {
  local tmpfile
  tmpfile=$(mktemp)
  local rc=0
  "${SDKPERF_CMD}" "${SDKPERF_CONN_ARGS[@]}" "$@" > "${tmpfile}" 2>&1 || rc=$?
  grep -v '^CLASSPATH:\|^JAVA:\|^SOLACE_VM_ARGS:' "${tmpfile}" || true
  rm -f "${tmpfile}"
  return ${rc}
}

run_sdkperf_bg() {
  local logfile="$1"; shift
  "${SDKPERF_CMD}" "${SDKPERF_CONN_ARGS[@]}" "$@" > "${logfile}" 2>&1 &
  echo $!
}

# ---------- MI container ----------
MI_CONTAINER="${MI_CONTAINER:-schema-validation-micro-integration}"

check_mi_health() {
  info "Checking MI reachability at ${MI_HOST}:${MI_PORT}..."
  local health
  health=$(curl -s "http://${MI_HOST}:${MI_PORT}/actuator/health" 2>/dev/null) || true
  if [[ -z "${health}" ]]; then
    error "MI not reachable at http://${MI_HOST}:${MI_PORT}/actuator/health"
    error "Is the MI running? Try: docker compose -f docker-compose.local.yml up -d --build"
    exit 1
  fi
  if echo "${health}" | grep -q '"UP"'; then
    success "MI is UP (healthy)"
  else
    warn "MI is reachable but health is: ${health}"
    warn "Continuing anyway — registry may be unreachable from container"
  fi
}

# Count MI events from Docker logs since a given UTC timestamp
# Usage: count_mi_events <since_utc> <event_type>
#   event_type: validated | rejected | processing_error
count_mi_events() {
  local since="$1" event_type="$2"
  local count
  count=$(docker logs --since "${since}" "${MI_CONTAINER}" 2>&1 | grep -c "event=${event_type}" || true)
  echo "${count:-0}"
}

get_mi_total_since() {
  local since="$1"
  local v r p
  v=$(count_mi_events "${since}" "validated")
  r=$(count_mi_events "${since}" "rejected")
  p=$(count_mi_events "${since}" "processing_error")
  echo $(( v + r + p ))
}

# Capture a UTC timestamp marker for log-based counting
# Usage: MARKER=$(utc_now)
utc_now() {
  python3 -c "from datetime import datetime,timezone; print(datetime.now(timezone.utc).strftime('%Y-%m-%dT%H:%M:%SZ'))"
}

snapshot_mi_counters() {
  local prefix="$1"
  local since="${LOG_MARKER}"
  eval "${prefix}_VALID=$(count_mi_events "${since}" validated)"
  eval "${prefix}_INVALID=$(count_mi_events "${since}" rejected)"
  eval "${prefix}_PROC_ERR=$(count_mi_events "${since}" processing_error)"
  eval "${prefix}_REG_ERR=0"
  eval "${prefix}_CACHE_TOPIC_HIT=0"
  eval "${prefix}_CACHE_TOPIC_MISS=0"
}

diff_mi_counters() {
  DELTA_VALID=$((AFTER_VALID - BEFORE_VALID))
  DELTA_INVALID=$((AFTER_INVALID - BEFORE_INVALID))
  DELTA_PROC_ERR=$((AFTER_PROC_ERR - BEFORE_PROC_ERR))
  DELTA_REG_ERR=0
  DELTA_CACHE_TOPIC_HIT=0
  DELTA_CACHE_TOPIC_MISS=0
  DELTA_TOTAL=$((DELTA_VALID + DELTA_INVALID + DELTA_PROC_ERR))
}

print_mi_counters() {
  info "MI counters: valid=${DELTA_VALID} invalid=${DELTA_INVALID} proc_err=${DELTA_PROC_ERR}"
}

# Poll MI Docker logs until expected total is reached or timeout
# Usage: wait_for_mi_processed <expected_total> <timeout_sec>
wait_for_mi_processed() {
  local expected=$1
  local timeout_sec=${2:-120}
  local since="${LOG_MARKER}"
  local elapsed=0
  while (( elapsed < timeout_sec )); do
    local current
    current=$(get_mi_total_since "${since}")
    if (( current >= expected )); then
      return 0
    fi
    sleep 1
    elapsed=$((elapsed + 1))
  done
  local final
  final=$(get_mi_total_since "${since}")
  warn "MI processed ${final}/${expected} within ${timeout_sec}s"
  return 1
}

# ---------- timing ----------
now_ms() {
  python3 -c "import time; print(int(time.time()*1000))"
}

format_duration() {
  local ms=$1
  if (( ms < 1000 )); then
    echo "${ms}ms"
  elif (( ms < 60000 )); then
    echo "$(( ms / 1000 )).$(( (ms % 1000) / 100 ))s"
  else
    echo "$(( ms / 60000 ))m $(( (ms % 60000) / 1000 ))s"
  fi
}

# ---------- background process management ----------
BG_PIDS=()

register_bg_pid() {
  BG_PIDS+=("$1")
}

cleanup_background() {
  if [[ ${#BG_PIDS[@]} -eq 0 ]]; then return; fi
  for pid in "${BG_PIDS[@]}"; do
    if kill -0 "${pid}" 2>/dev/null; then
      kill "${pid}" 2>/dev/null || true
    fi
  done
}

wait_for_pid() {
  local pid=$1
  local timeout_sec=${2:-120}
  local elapsed=0
  while kill -0 "${pid}" 2>/dev/null; do
    sleep 1
    elapsed=$((elapsed + 1))
    if (( elapsed >= timeout_sec )); then
      warn "Timeout (${timeout_sec}s) waiting for PID ${pid}"
      kill "${pid}" 2>/dev/null || true
      return 1
    fi
  done
  wait "${pid}" 2>/dev/null || true
  return 0
}

# ---------- results directory ----------
RESULTS_DIR=""

ensure_results_dir() {
  local ts
  ts=$(date +%Y%m%d-%H%M%S)
  RESULTS_DIR="${ROOT_DIR}/.runtime/performance/${ts}"
  mkdir -p "${RESULTS_DIR}"
  info "Results directory: ${RESULTS_DIR}"
}

# ---------- summary table ----------
print_result_row() {
  local label="$1" value="$2" unit="${3:-}"
  printf "  %-28s %10s %s\n" "${label}" "${value}" "${unit}" >&2
}

print_pass_fail() {
  local label="$1" passed="$2"
  if [[ "${passed}" == "true" ]]; then
    printf "  %-28s ${GREEN}%10s${NC}\n" "${label}" "PASS" >&2
  else
    printf "  %-28s ${RED}%10s${NC}\n" "${label}" "FAIL" >&2
  fi
}

# ---------- HA helpers ----------
HA_CONTAINERS=("mi-1" "mi-2" "mi-3")
HA_PORTS=("8088" "8089" "8090")

check_ha_health() {
  info "Checking HA cluster health..."
  local all_up=true
  for i in "${!HA_CONTAINERS[@]}"; do
    local c="${HA_CONTAINERS[$i]}"
    local p="${HA_PORTS[$i]}"
    local health
    health=$(curl -s "http://localhost:${p}/actuator/health" 2>/dev/null) || true
    if echo "${health}" | grep -q '"UP"'; then
      success "${c} (port ${p}): UP"
    elif [[ -n "${health}" ]]; then
      warn "${c} (port ${p}): ${health}"
    else
      error "${c} (port ${p}): unreachable"
      all_up=false
    fi
  done
  if [[ "${all_up}" != "true" ]]; then
    error "Not all HA instances are reachable"
    return 1
  fi
}

count_ha_events() {
  local since="$1" event_type="$2"
  local total=0
  for c in "${HA_CONTAINERS[@]}"; do
    local count
    count=$(docker logs --since "${since}" "${c}" 2>&1 | grep -c "event=${event_type}" || true)
    total=$((total + count))
  done
  echo "${total}"
}

get_ha_total_since() {
  local since="$1"
  local v r p
  v=$(count_ha_events "${since}" "validated")
  r=$(count_ha_events "${since}" "rejected")
  p=$(count_ha_events "${since}" "processing_error")
  echo $(( v + r + p ))
}

count_ha_events_per_instance() {
  local since="$1" event_type="$2"
  for c in "${HA_CONTAINERS[@]}"; do
    local count
    count=$(docker logs --since "${since}" "${c}" 2>&1 | grep -c "event=${event_type}" || true)
    echo "${c}:${count}"
  done
}

get_ha_per_instance_totals() {
  local since="$1"
  for c in "${HA_CONTAINERS[@]}"; do
    local v r p
    v=$(docker logs --since "${since}" "${c}" 2>&1 | grep -c "event=validated" || true)
    r=$(docker logs --since "${since}" "${c}" 2>&1 | grep -c "event=rejected" || true)
    p=$(docker logs --since "${since}" "${c}" 2>&1 | grep -c "event=processing_error" || true)
    echo "${c}:$((v + r + p)):${v}:${r}:${p}"
  done
}

wait_for_ha_processed() {
  local expected=$1
  local timeout_sec=${2:-120}
  local since="${LOG_MARKER}"
  local elapsed=0
  while (( elapsed < timeout_sec )); do
    local current
    current=$(get_ha_total_since "${since}")
    if (( current >= expected )); then
      return 0
    fi
    sleep 1
    elapsed=$((elapsed + 1))
  done
  local final
  final=$(get_ha_total_since "${since}")
  warn "HA cluster processed ${final}/${expected} within ${timeout_sec}s"
  return 1
}

wait_for_container_healthy() {
  local container="$1" port="$2" timeout_sec="${3:-60}"
  local elapsed=0
  while (( elapsed < timeout_sec )); do
    if curl -s "http://localhost:${port}/actuator/health" 2>/dev/null | grep -q '"UP"'; then
      return 0
    fi
    sleep 2
    elapsed=$((elapsed + 2))
  done
  return 1
}
