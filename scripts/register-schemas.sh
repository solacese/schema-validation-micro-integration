#!/usr/bin/env bash
# Register manufacturing IoT schemas into the Solace Schema Registry.
#
# Schemas (group: manufacturing-iot):
#   metadata          - shared envelope header (topic, timestamp, message_id, seq)
#   context-generic   - flexible business context object
#   pub-files         - file-transfer payload (references metadata + context-generic)
#   pub-table         - tabular-data payload  (references metadata + context-generic)
#
# Override defaults with environment variables:
#   SCHEMA_REGISTRY_BASE_URL   (default: http://localhost:18081/apis/registry/v3)
#   SCHEMA_REGISTRY_USERNAME   (default: sr-developer)
#   SCHEMA_REGISTRY_PASSWORD   (default: devPassword)

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
BASE_URL="${SCHEMA_REGISTRY_BASE_URL:-http://localhost:18081/apis/registry/v3}"
USERNAME="${SCHEMA_REGISTRY_USERNAME:-sr-developer}"
PASSWORD="${SCHEMA_REGISTRY_PASSWORD:-devPassword}"
GROUP_ID="manufacturing-iot"

auth_args=(-u "${USERNAME}:${PASSWORD}" -H "Content-Type: application/json")

request() {
  local method="$1"
  local path="$2"
  shift 2
  curl -fsS -X "${method}" "${auth_args[@]}" "${BASE_URL}${path}" "$@"
}

artifact_exists() {
  local artifact_id="$1"
  local status
  status=$(curl -s -o /dev/null -w "%{http_code}" -u "${USERNAME}:${PASSWORD}" \
    "${BASE_URL}/groups/${GROUP_ID}/artifacts/${artifact_id}")
  [[ "${status}" == "200" ]]
}

version_exists() {
  local artifact_id="$1"
  local version="$2"
  local status
  status=$(curl -s -o /dev/null -w "%{http_code}" -u "${USERNAME}:${PASSWORD}" \
    "${BASE_URL}/groups/${GROUP_ID}/artifacts/${artifact_id}/versions/${version}")
  [[ "${status}" == "200" ]]
}

build_first_version_payload() {
  local artifact_id="$1"
  local artifact_type="$2"
  local version="$3"
  local content_type="$4"
  local file_path="$5"
  local refs_json="$6"
  python3 - "$artifact_id" "$artifact_type" "$version" "$content_type" "$file_path" "$refs_json" <<'PY'
import json
import pathlib
import sys

artifact_id, artifact_type, version, content_type, file_path, refs_json = sys.argv[1:]
payload = {
    "artifactId": artifact_id,
    "artifactType": artifact_type,
    "firstVersion": {
        "version": version,
        "content": {
            "contentType": content_type,
            "content": pathlib.Path(file_path).read_text(),
        },
    },
}
refs = json.loads(refs_json)
if refs:
    payload["firstVersion"]["content"]["references"] = refs
print(json.dumps(payload))
PY
}

build_new_version_payload() {
  local version="$1"
  local content_type="$2"
  local file_path="$3"
  local refs_json="$4"
  python3 - "$version" "$content_type" "$file_path" "$refs_json" <<'PY'
import json
import pathlib
import sys

version, content_type, file_path, refs_json = sys.argv[1:]
payload = {
    "version": version,
    "content": {
        "contentType": content_type,
        "content": pathlib.Path(file_path).read_text(),
    },
}
refs = json.loads(refs_json)
if refs:
    payload["content"]["references"] = refs
print(json.dumps(payload))
PY
}

upsert_artifact_version() {
  local artifact_id="$1"
  local artifact_type="$2"
  local version="$3"
  local content_type="$4"
  local file_path="$5"
  local refs_json="${6:-[]}"
  local payload
  if artifact_exists "${artifact_id}"; then
    if version_exists "${artifact_id}" "${version}"; then
      echo "Already registered ${artifact_id}:${version}"
      return
    fi
    payload="$(build_new_version_payload "${version}" "${content_type}" "${file_path}" "${refs_json}")"
    request POST "/groups/${GROUP_ID}/artifacts/${artifact_id}/versions" --data "${payload}" >/dev/null
  else
    payload="$(build_first_version_payload "${artifact_id}" "${artifact_type}" "${version}" "${content_type}" "${file_path}" "${refs_json}")"
    request POST "/groups/${GROUP_ID}/artifacts" --data "${payload}" >/dev/null
  fi
  echo "Registered ${artifact_id}:${version}"
}

# ── Shared schemas (no references) ───────────────────────────────────────────

upsert_artifact_version "metadata" "JSON" "1.0.0" "application/json" \
  "${ROOT_DIR}/schemas/json/mqtt-envelope/metadata.v1.schema.json"

upsert_artifact_version "context-generic" "JSON" "1.0.0" "application/json" \
  "${ROOT_DIR}/schemas/json/mqtt-envelope/context.generic.v1.schema.json"

# ── Composite schemas ─────────────────────────────────────────────────────────

upsert_artifact_version "pub-files" "JSON" "1.0.0" "application/json" \
  "${ROOT_DIR}/schemas/json/mqtt-envelope/pub-files.v1.schema.json" \
  '[
    {"groupId":"'"${GROUP_ID}"'","artifactId":"metadata","version":"1.0.0","name":"https://example.com/schemas/mqtt-envelope/metadata.v1.schema.json"},
    {"groupId":"'"${GROUP_ID}"'","artifactId":"context-generic","version":"1.0.0","name":"https://example.com/schemas/mqtt-envelope/context.generic.v1.schema.json"}
  ]'

upsert_artifact_version "pub-table" "JSON" "1.0.0" "application/json" \
  "${ROOT_DIR}/schemas/json/mqtt-envelope/pub-table.v1.schema.json" \
  '[
    {"groupId":"'"${GROUP_ID}"'","artifactId":"metadata","version":"1.0.0","name":"https://example.com/schemas/mqtt-envelope/metadata.v1.schema.json"},
    {"groupId":"'"${GROUP_ID}"'","artifactId":"context-generic","version":"1.0.0","name":"https://example.com/schemas/mqtt-envelope/context.generic.v1.schema.json"}
  ]'

echo "Manufacturing IoT schemas registered into ${BASE_URL} group ${GROUP_ID}"
