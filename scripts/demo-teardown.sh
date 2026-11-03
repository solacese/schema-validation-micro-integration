#!/usr/bin/env bash
# Stop demo services while preserving the shared network and registry data.
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"
source "${ROOT_DIR}/scripts/lib/env.sh"
load_project_env
# stop/rm avoids one Compose file deleting a network used by the other files.
for compose in docker-compose.local.yml docker-compose.ha.yml docker-compose.broker.yml docker-compose.registry-native.yml; do
  IDP_KEY="${IDP_KEY:-unused-during-stop}" docker compose -f "${compose}" stop
  IDP_KEY="${IDP_KEY:-unused-during-stop}" docker compose -f "${compose}" rm -f
 done
echo "Demo services stopped. Registry data volumes are preserved."
