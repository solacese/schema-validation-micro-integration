#!/usr/bin/env bash
# Downloads SDKPerf JCSMP for performance testing
# SDKPerf is not included in the repo — run this before performance tests
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SDKPERF_VERSION="${SDKPERF_VERSION:-10.29.1}"
SDKPERF_DIR="${SCRIPT_DIR}/sdkperf-jcsmp-${SDKPERF_VERSION}"

if [[ -d "${SDKPERF_DIR}" && -f "${SDKPERF_DIR}/sdkperf_java.sh" ]]; then
  echo "SDKPerf ${SDKPERF_VERSION} already present at ${SDKPERF_DIR}"
  exit 0
fi

echo "SDKPerf JCSMP ${SDKPERF_VERSION} is required for performance tests."
echo ""
echo "Download it from Solace:"
echo "  https://solace.com/downloads/#sdkperf"
echo ""
echo "Then extract to: ${SDKPERF_DIR}/"
echo ""
echo "Expected structure:"
echo "  ${SDKPERF_DIR}/sdkperf_java.sh"
echo "  ${SDKPERF_DIR}/lib/*.jar"
echo ""
echo "Or if you have the archive:"
echo "  tar xzf sdkperf-jcsmp-${SDKPERF_VERSION}.tar.gz -C ${SCRIPT_DIR}/"
exit 1
