#!/usr/bin/env bash
# Install the local stack, or connect only the MI to configured existing services.
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"
source "${ROOT_DIR}/scripts/lib/env.sh"
load_project_env
if [[ -f "${ROOT_DIR}/lib/schema-validation-micro-integration.jar" ]]; then
  export MI_ARTIFACT_STAGE="${MI_ARTIFACT_STAGE:-packaged}"
fi
case "${1:-}" in
  "") exec "${ROOT_DIR}/scripts/demo-setup.sh" ;;
  --existing)
    if [[ ! -f .env ]]; then
      echo "Copy .env.example to .env and configure your broker and registry, then retry." >&2
      exit 1
    fi
    exec docker compose -f docker-compose.local.yml up -d --build --wait
    ;;
  -h|--help)
    echo "Usage: ./scripts/install.sh [--existing]"
    echo "Default: start the local registry, broker, and micro-integration."
    echo "--existing: start only the MI using the broker and registry configured in .env."
    ;;
  *) echo "Unknown option: $1. Use --help." >&2; exit 2 ;;
esac
