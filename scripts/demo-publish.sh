#!/usr/bin/env bash
# Publish demo messages for live flow viewing — Manufacturing IoT topic space
# Usage: ./scripts/demo-publish.sh <type> [valid|invalid]
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
SDKPERF="${SDKPERF_CMD:-${ROOT_DIR}/performance/sdkperf-jcsmp-${SDKPERF_VERSION:-10.29.1}/sdkperf_java.sh}"

source "${ROOT_DIR}/scripts/lib/env.sh"
load_project_env

PUB_USER="${SOLACE_INPUT_USERNAME:-default}"
PUB_PASS="${SOLACE_INPUT_PASSWORD:-default}"

# Build REST messaging URL for Solace (works with both local and cloud brokers)
build_rest_url() {
  local broker="${SOLACE_INPUT_BROKER:-tcp://solace-broker:55555}"
  if [[ "${broker}" == wss://* ]]; then
    # Solace Cloud: REST messaging on HTTPS port 9443
    local host
    host=$(echo "${broker}" | sed -E 's|^wss://([^:/]+).*|\1|')
    echo "https://${host}:9443"
  elif [[ "${broker}" == *solace-broker* ]]; then
    echo "http://localhost:9000"
  else
    local host port
    host=$(echo "${broker}" | sed -E 's|^[a-z]+://([^:/]+).*|\1|')
    port=9000
    echo "http://${host}:${port}"
  fi
}

# Build SDKPerf connection URL (SMF protocol)
build_sdkperf_url() {
  local url="${SOLACE_INPUT_BROKER:-tcp://solace-broker:55555}"
  url="$(host_smf_url "${url}")"
  if [[ "${url}" == wss://* ]]; then
    url="${url/wss:\/\//tcps://}"
    url="${url/:443/:55443}"
  fi
  echo "${url}"
}

publish() {
  local topic="$1" file="$2" label="$3" schema_group="${4:-}" schema_name="${5:-}" schema_version="${6:-}"
  echo "Publishing: ${label}"
  echo "  Topic:   ${topic}"
  echo "  Payload: $(basename "${file}")"
  if [[ -n "${schema_group}" ]]; then
    echo "  Schema:  ${schema_group}/${schema_name}:${schema_version}"
  fi

  # Build schema header args for SDKPerf (-cpl=) and REST (Solace-User-Property-*)
  local schema_headers=()
  local rest_schema_headers=()
  if [[ -n "${schema_group}" ]]; then
    schema_headers+=("-cpl=String,schema_group,${schema_group},String,schema_name,${schema_name},String,schema_version,${schema_version}")
    rest_schema_headers+=(-H "Solace-User-Property-schema_group: ${schema_group}")
    rest_schema_headers+=(-H "Solace-User-Property-schema_name: ${schema_name}")
    rest_schema_headers+=(-H "Solace-User-Property-schema_version: ${schema_version}")
  fi

  if [[ -x "${SDKPERF}" ]]; then
    # Prefer SDKPerf if available
    local sdkperf_url vpn
    sdkperf_url=$(build_sdkperf_url)
    vpn="${SOLACE_INPUT_VPN:-default}"
    "${SDKPERF}" -cip="${sdkperf_url}" -cu="${PUB_USER}@${vpn}" -cp="${PUB_PASS}" \
      -ptl="${topic}" -pfl="${file}" -mt=persistent -mn=1 -mr=1 -ped=2 \
      "${schema_headers[@]}" 2>&1 \
      | grep -v '^CLASSPATH\|^JAVA:\|^SOLACE_VM'
  else
    # Fallback: Solace REST messaging
    local rest_url
    rest_url=$(build_rest_url)
    local http_code
    http_code=$(curl -s -o /dev/null -w "%{http_code}" -X POST \
      "${rest_url}/TOPIC/${topic}" \
      -u "${PUB_USER}:${PUB_PASS}" \
      -H "Content-Type: application/json" \
      -H "Solace-Delivery-Mode: persistent" \
      "${rest_schema_headers[@]}" \
      -d "@${file}")
    if [[ "${http_code}" == "200" ]]; then
      echo "  Result:  OK (REST ${http_code})"
    else
      echo "  Result:  FAILED (REST ${http_code})" >&2
      return 1
    fi
  fi
  echo ""
}

TYPE="${1:-help}"
VALIDITY="${2:-valid}"

case "${TYPE}" in

  # ── Valid messages (5 scenarios) ─────────────────────────────────────

  pub-files|files)
    if [[ "${VALIDITY}" == "valid" ]]; then
      publish "Acme/SA/PMNO-001/DATASCIENCE/JSON-v1/FromEqt/PUB_FILES" \
        "${ROOT_DIR}/samples/json/acme-pub-files.valid.json" \
        "PUB_FILES valid — Assembly CSV + media from SiteA (expects: validated)" \
        "manufacturing-iot" "pub-files" "1.0.0"
    else
      publish "Acme/SA/PMNO-001/DATASCIENCE/JSON-v1/FromEqt/PUB_FILES" \
        "${ROOT_DIR}/samples/json/acme-pub-files.invalid.json" \
        "PUB_FILES invalid — missing message_id (expects: rejected)" \
        "manufacturing-iot" "pub-files" "1.0.0"
    fi
    ;;
  pub-table|table)
    if [[ "${VALIDITY}" == "valid" ]]; then
      publish "Acme/SB/PMNO-042/DATASCIENCE/JSON-v1/FromEqt/PUB_TABLE" \
        "${ROOT_DIR}/samples/json/acme-pub-table.valid.json" \
        "PUB_TABLE valid — Sensor-Array telemetry from SiteB (expects: validated)" \
        "manufacturing-iot" "pub-table" "1.0.0"
    else
      publish "Acme/SB/PMNO-042/DATASCIENCE/JSON-v1/FromEqt/PUB_TABLE" \
        "${ROOT_DIR}/samples/json/acme-pub-table.invalid.json" \
        "PUB_TABLE invalid — empty table array (expects: rejected)" \
        "manufacturing-iot" "pub-table" "1.0.0"
    fi
    ;;
  shopfloor)
    publish "Acme/SC/PMNO-100/SHOPFLOOR/JSON-v1/FromEqt/PUB_TABLE" \
      "${ROOT_DIR}/samples/json/acme-pub-table.valid.json" \
      "SHOPFLOOR PUB_TABLE — SiteC shopfloor data (expects: validated)" \
      "manufacturing-iot" "pub-table" "1.0.0"
    ;;
  shopfloor-si)
    publish "Acme/SA/PMNO-001/SHOPFLOOR/JSON-v1/FromSI/PUB_TABLE" \
      "${ROOT_DIR}/samples/json/acme-shopfloor-fromsi.valid.json" \
      "SHOPFLOOR FromSI — MES-Gateway integration from SiteA (expects: validated)" \
      "manufacturing-iot" "pub-table" "1.0.0"
    ;;
  apex)
    publish "Apex/SF/PMNO-200/DATASCIENCE/JSON-v1/FromEqt/PUB_FILES" \
      "${ROOT_DIR}/samples/json/acme-pub-files.valid.json" \
      "Apex PUB_FILES — SiteF file transfer (expects: validated)" \
      "manufacturing-iot" "pub-files" "1.0.0"
    ;;
  apex-table)
    publish "Apex/SF/PMNO-200/DATASCIENCE/JSON-v1/FromEqt/PUB_TABLE" \
      "${ROOT_DIR}/samples/json/acme-pub-table.valid.json" \
      "Apex PUB_TABLE — SiteF sensor data (expects: validated)" \
      "manufacturing-iot" "pub-table" "1.0.0"
    ;;

  # ── Schema violations (4 scenarios) ─────────────────────────────────

  bad-id)
    publish "Acme/SA/PMNO-001/DATASCIENCE/JSON-v1/FromEqt/PUB_FILES" \
      "${ROOT_DIR}/samples/json/acme-pub-files.invalid.json" \
      "Missing message_id — required UUID field absent (expects: rejected)" \
      "manufacturing-iot" "pub-files" "1.0.0"
    ;;
  bad-checksum)
    publish "Acme/SD/PMNO-055/DATASCIENCE/JSON-v1/FromEqt/PUB_FILES" \
      "${ROOT_DIR}/samples/json/acme-pub-files.bad-checksum.json" \
      "Bad checksum — sha-256 value is not 64 hex chars (expects: rejected)" \
      "manufacturing-iot" "pub-files" "1.0.0"
    ;;
  bad-doctype)
    publish "Acme/SE/PMNO-077/DATASCIENCE/JSON-v1/FromEqt/PUB_FILES" \
      "${ROOT_DIR}/samples/json/acme-pub-files.bad-doctype.json" \
      "Bad document_type — 'Spreadsheet' not in enum [Media,Tableau] (expects: rejected)" \
      "manufacturing-iot" "pub-files" "1.0.0"
    ;;
  bad-payload)
    publish "Acme/SB/PMNO-042/DATASCIENCE/JSON-v1/FromEqt/PUB_TABLE" \
      "${ROOT_DIR}/samples/json/acme-pub-table.not-json.bin" \
      "Not JSON — binary garbage from legacy NCOM protocol (expects: rejected)" \
      "manufacturing-iot" "pub-table" "1.0.0"
    ;;
  empty-table)
    publish "Acme/SB/PMNO-042/DATASCIENCE/JSON-v1/FromEqt/PUB_TABLE" \
      "${ROOT_DIR}/samples/json/acme-pub-table.invalid.json" \
      "Empty table — minItems:1 violated (expects: rejected)" \
      "manufacturing-iot" "pub-table" "1.0.0"
    ;;

  # ── Infrastructure edge cases ───────────────────────────────────────

  unmapped)
    publish "Acme/SA/PMNO-001/STATE/JSON-v1/FromEqt/HEARTBEAT" \
      "${ROOT_DIR}/samples/json/acme-pub-files.valid.json" \
      "Unmapped topic — STATE category not subscribed (will not reach MI)"
    ;;

  # ── Publish all (12 events) ─────────────────────────────────────────

  all)
    echo "  Publishing 12 demo events (pause between each for visibility)"
    echo ""
    echo "  ── Valid messages ──"
    "$0" pub-files valid
    sleep 1
    "$0" pub-table valid
    sleep 1
    "$0" shopfloor
    sleep 1
    "$0" shopfloor-si
    sleep 1
    "$0" apex
    sleep 1
    "$0" apex-table
    sleep 1
    echo "  ── Schema violations ──"
    "$0" bad-id
    sleep 1
    "$0" bad-checksum
    sleep 1
    "$0" bad-doctype
    sleep 1
    "$0" bad-payload
    sleep 1
    "$0" empty-table
    sleep 1
    echo "  ── Edge cases ──"
    "$0" unmapped
    ;;
  help|*)
    echo "Usage: ./scripts/demo-publish.sh <type> [valid|invalid]"
    echo ""
    echo "  Manufacturing IoT Topic Space:"
    echo "    {ENTERPRISE}/{SITE}/{PMNO}/{CATEGORY}/{PROTOCOL-Version}/{SOURCE}/{MESSAGE}"
    echo ""
    echo "  Valid messages:"
    echo "    pub-files valid     Assembly CSV + media from SiteA"
    echo "    pub-table valid     Sensor-Array telemetry from SiteB"
    echo "    shopfloor           SHOPFLOOR from SiteC"
    echo "    shopfloor-si        SHOPFLOOR FromSI (MES-Gateway) from SiteA"
    echo "    apex               Apex PUB_FILES from SiteF"
    echo "    apex-table         Apex PUB_TABLE from SiteF"
    echo ""
    echo "  Schema violations:"
    echo "    bad-id              Missing required message_id"
    echo "    bad-checksum        Invalid sha-256 hex value"
    echo "    bad-doctype         document_type not in enum"
    echo "    bad-payload         Binary data (not valid JSON)"
    echo "    empty-table         Table array with 0 items"
    echo ""
    echo "  Edge cases:"
    echo "    unmapped            STATE category (not subscribed)"
    echo ""
    echo "  all                   Publish all 12 events in sequence"
    ;;
esac
