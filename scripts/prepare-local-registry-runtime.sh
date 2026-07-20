#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNTIME_DIR="${ROOT_DIR}/.runtime"
DIST_DIR="${RUNTIME_DIR}/schema-registry"
BACKEND_DIR="${RUNTIME_DIR}/registry-backend"
BACKEND_TAR="${ROOT_DIR}/schema-registry-v1.1.0/docker-images/solace-registry-v1.1.0.tar.gz"
DIST_TAR="${ROOT_DIR}/schema-registry-v1.1.0/compose-files/solace-schema-registry-dist.tar.gz"

mkdir -p "${RUNTIME_DIR}"

if [[ ! -f "${DIST_DIR}/compose.yaml" ]]; then
  mkdir -p "${DIST_DIR}"
  tar -xzf "${DIST_TAR}" -C "${DIST_DIR}"
fi

if [[ ! -f "${BACKEND_DIR}/deployments/solace-schema-registry-3.0.7-SNAPSHOT-runner.jar" ]]; then
  mkdir -p "${BACKEND_DIR}"
  python3 - "${BACKEND_TAR}" "${BACKEND_DIR}" <<'PY'
import json
import os
import sys
import tarfile

backend_tar, output_dir = sys.argv[1:]
with tarfile.open(backend_tar, "r:gz") as image_tar:
    manifest = json.load(image_tar.extractfile("manifest.json"))[0]
    for layer_name in manifest["Layers"][-2:]:
        with tarfile.open(fileobj=image_tar.extractfile(layer_name), mode="r:") as layer_tar:
            members = [m for m in layer_tar.getmembers() if m.name.startswith("deployments/")]
            layer_tar.extractall(output_dir, members=members)
PY
fi

echo "Registry runtime prepared under ${RUNTIME_DIR}"
