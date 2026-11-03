#!/usr/bin/env bash
# Build, verify, and package exactly the committed release source.
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "${ROOT_DIR}"
if [[ -n "$(git status --porcelain)" ]]; then
  echo "Commit or stash changes before packaging: release archives must match their commit." >&2
  exit 1
fi
: "${JAVA_HOME:?Set JAVA_HOME to a JDK 17+ installation before packaging}"
VERSION=$(python3 - <<'PY'
import xml.etree.ElementTree as E
root = E.parse('pom.xml').getroot()
print(root.find('{http://maven.apache.org/POM/4.0.0}version').text)
PY
)
if [[ ! "${VERSION}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "Expected a release version in pom.xml, got ${VERSION}" >&2
  exit 1
fi
export SOURCE_DATE_EPOCH="$(git show -s --format=%ct HEAD)"
export MAVEN_ARGS="${MAVEN_ARGS:-} -Dproject.build.outputTimestamp=${SOURCE_DATE_EPOCH}"
./scripts/verify.sh
mvn -B org.cyclonedx:cyclonedx-maven-plugin:2.9.3:makeAggregateBom \
  -DincludeTestScope=false -DincludeBomSerialNumber=false -DoutputFormat=all
mkdir -p target/release-healthcheck
"${JAVA_HOME}/bin/javac" --release 17 -d target/release-healthcheck src/docker/Healthcheck.java
JAR_DATE=$(python3 -c 'import datetime,os; print(datetime.datetime.fromtimestamp(int(os.environ["SOURCE_DATE_EPOCH"]), datetime.timezone.utc).isoformat())')
"${JAVA_HOME}/bin/jar" --create --date="${JAR_DATE}" --file target/healthcheck.jar \
  -C target/release-healthcheck Healthcheck.class
python3 scripts/package-release.py
