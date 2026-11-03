#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"
source "${ROOT_DIR}/scripts/lib/env.sh"
load_project_env
export SCHEMA_REGISTRY_VERSION="${SCHEMA_REGISTRY_VERSION:-1.1.2}"
export SCHEMA_REGISTRY_RUNTIME_DIR="${SCHEMA_REGISTRY_RUNTIME_DIR:-${ROOT_DIR}/.runtime/schema-registry/${SCHEMA_REGISTRY_VERSION}}"
"${ROOT_DIR}/scripts/prepare-local-registry-runtime.sh" "$@"

# The embedded IdP expects the signing key shipped in the release's .env.
# Source it in a subshell so its port defaults do not replace our local ports.
if [[ -z "${IDP_KEY:-}" ]]; then
  IDP_KEY=$(set -a; source "${SCHEMA_REGISTRY_RUNTIME_DIR}/compose-files/.env"; printf '%s' "${IDP_KEY}")
  export IDP_KEY
fi
docker compose -f "${ROOT_DIR}/docker-compose.registry-native.yml" up -d
