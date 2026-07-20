#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
RUNTIME_DIR="${ROOT_DIR}/.runtime/solace-pretty-dump-release"
PRETTY_DUMP_VERSION="${PRETTY_DUMP_VERSION:-1.1.1}"
PRETTY_DUMP_ARCHIVE="${RUNTIME_DIR}/solace-pretty-dump-${PRETTY_DUMP_VERSION}.tar"
PRETTY_DUMP_URL="https://github.com/SolaceLabs/solace-pretty-dump/releases/download/v${PRETTY_DUMP_VERSION}/solace-pretty-dump-${PRETTY_DUMP_VERSION}.tar"
PRETTY_DUMP_HOME="${RUNTIME_DIR}/solace-pretty-dump-${PRETTY_DUMP_VERSION}"

mkdir -p "${RUNTIME_DIR}"

if [[ ! -f "${PRETTY_DUMP_ARCHIVE}" ]]; then
  curl -fL -o "${PRETTY_DUMP_ARCHIVE}" "${PRETTY_DUMP_URL}"
fi

if [[ ! -x "${PRETTY_DUMP_HOME}/bin/prettydump" ]]; then
  tar -xf "${PRETTY_DUMP_ARCHIVE}" -C "${RUNTIME_DIR}"
fi

echo "PrettyDump ready at ${PRETTY_DUMP_HOME}/bin/prettydump"
