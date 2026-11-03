#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"
while IFS= read -r script; do bash -n "${script}"; done < <(find scripts performance -name '*.sh' -type f)
python3 -m unittest discover -s scripts/tests -v
mvn -B verify
# RegistryCompatibilityIT runs when MI_REGISTRY_TEST_URL is set, otherwise skips.
