#!/usr/bin/env bash
# Stop all demo containers
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"

echo "Stopping MI..."
docker compose -f docker-compose.local.yml down 2>/dev/null || true
docker compose -f docker-compose.ha.yml down 2>/dev/null || true

echo "Stopping broker..."
docker compose -f docker-compose.broker.yml down 2>/dev/null || true

echo "Stopping registry..."
docker compose -f docker-compose.registry-native.yml down 2>/dev/null || true

echo "Done. All demo containers stopped."
echo "  To also remove data volumes: docker compose -f docker-compose.registry-native.yml down -v"
