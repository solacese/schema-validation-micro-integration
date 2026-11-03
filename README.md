# Schema Validation Micro-Integration

[![Verify](https://github.com/solacese/schema-validation-micro-integration/actions/workflows/verify.yml/badge.svg)](https://github.com/solacese/schema-validation-micro-integration/actions/workflows/verify.yml)
[![Release](https://img.shields.io/github/v/release/solacese/schema-validation-micro-integration)](https://github.com/solacese/schema-validation-micro-integration/releases/latest)

Validate **JSON Schema, XML Schema, Avro, and Protobuf** messages using **Solace Schema Registry 1.1.2 GA**. Resolve schemas from message properties or topic mappings, validate the payload, and republish it with the outcome and schema metadata. Supports one broker, separate input/output brokers, and multiple instances sharing a queue.

## Install a release

For the complete walkthrough, use the [Word installation and setup guide](docs/Schema-Validation-MI-Installation-Guide.docx). It covers each installation path, connection settings, and working examples for all four formats.

**Requirements:** Docker with Compose v2+, Python 3.9+, and curl. Release bundles contain prebuilt Java artifacts; no local Java, Maven, or application compilation is needed for Docker installation.

1. Download `schema-registry-v1.1.2.tar.gz` and its `.md5` file from [Solace Products](https://products.solace.com/products/1.1.2GA/Schema_Registry/solace-schema-registry/) into your Downloads folder. Leave them compressed.
2. Start Docker Desktop and sign in if required by your organization.
3. Download and install this integration:

```bash
curl -fLO https://github.com/solacese/schema-validation-micro-integration/releases/download/v1.0.2/schema-validation-micro-integration-1.0.2.tar.gz
curl -fLO https://github.com/solacese/schema-validation-micro-integration/releases/download/v1.0.2/SHA256SUMS
awk '$2 == "schema-validation-micro-integration-1.0.2.tar.gz" {print}' SHA256SUMS | shasum -a 256 -c -
tar -xzf schema-validation-micro-integration-1.0.2.tar.gz
cd schema-validation-micro-integration-1.0.2
./scripts/install.sh
```

The checksum command must report `OK` before you extract the archive. On Windows, run these commands in WSL2 with Docker integration enabled.

Setup prepares the registry runtime, starts PostgreSQL and the identity provider, starts the broker, registers sample schemas, and starts the MI. It waits for healthy services and can be re-run. For an archive elsewhere, set `SCHEMA_REGISTRY_DIST=/path/to/schema-registry-v1.1.2.tar.gz` before installing. Public base images still require network access on first installation. The licensed registry distribution is downloaded separately and is not redistributed in this release.

| Service | Address |
|---|---|
| MI health | [localhost:8088/actuator/health](http://localhost:8088/actuator/health) |
| Registry API | [localhost:18081/apis/registry/v3/system/info](http://localhost:18081/apis/registry/v3/system/info) |
| Broker management | [localhost:8080](http://localhost:8080), local demo login `admin` / `admin` |

The local API stack works on Apple Silicon and x86. It does not include the registry web console.

## Use existing services

From a release bundle or source checkout:

```bash
cp .env.example .env
# Set SOLACE_INPUT_*, SOLACE_OUTPUT_*, and SCHEMA_REGISTRY_* in .env.
./scripts/install.sh --existing
```

Point `SCHEMA_REGISTRY_BASE_URL` at an endpoint ending in `/apis/registry/v3`, reachable from Docker. Use `host.docker.internal` for a registry on this Mac. Mount optional configuration as `config/application.yml`. Change `app.ingress.topic-subscriptions` from the manufacturing demo defaults to your own topics.

A container image is also published for `linux/amd64` and `linux/arm64`. Solace organization policy keeps GHCR packages private. Sign in with an account and GitHub Packages credentials that can read this package before pulling:

```bash
docker login ghcr.io
docker pull ghcr.io/solacese/schema-validation-micro-integration:1.0.2
```

For Java deployments, the release includes an executable JAR. With **Java 17+** and connection settings exported in the shell:

```bash
java -jar lib/schema-validation-micro-integration.jar \
  --spring.config.additional-location=optional:file:./config/
```

## Supported formats

| Registry type | Payload | References |
|---|---|---|
| `JSON` | UTF-8 JSON, explicit `$schema` dialect or 2020-12 by default | Registry-backed `$ref` |
| `XML` / `XSD` | XML against XSD 1.0, including declared XML encodings | Registry-backed includes/imports |
| `AVRO` | One raw binary datum | Referenced named types |
| `PROTOBUF` | Raw binary message; proto2 required fields are checked | Imports, well-known types, explicit root selection |

The original payload is preserved. Avro container files and serializer-specific envelopes, including Confluent magic-byte/schema-ID framing, must be decoded before ingress. Proto3 permits missing scalar fields and unknown fields. XML external DTDs and remote schemas are blocked; register dependencies as references.

Messages can select any supported format using these user properties:

```text
schema_group = manufacturing-iot
schema_name = pub-files
schema_version = 1.0.0
```

`schema_version` is optional; by default the most recently created enabled version is selected. The registry determines the format. Without schema properties, topic mappings select the artifact. Output defaults to the original topic, with `mi_validation_status` set to `VALIDATED`, `REJECT`, or `PROCESSING_ERROR`. Configurable topic templates can route each outcome separately, including for MQTT clients without user properties.

## Try the demo

```bash
./scripts/demo-publish.sh pub-files valid
./scripts/demo-publish.sh pub-files invalid
./scripts/demo-publish.sh all
./scripts/demo-teardown.sh  # stop services; preserve registry data
```

Register your own schema using the file extension, or specify `--type`:

```bash
python3 scripts/register-schema.py sensor.xsd --artifact sensor --version 1.0.0
python3 scripts/register-schema.py reading.avsc --artifact reading --version 1.0.0
python3 scripts/register-schema.py reading.proto --artifact reading --references refs.json
```

For remote registration, export `SCHEMA_REGISTRY_BASE_URL`, `SCHEMA_REGISTRY_USERNAME`, and `SCHEMA_REGISTRY_PASSWORD` in the host shell. Use a developer account. Register dependencies first; reference objects contain `groupId`, `artifactId`, `version`, and `name`.

## Develop and verify

Use Java 17+ and Maven 3.9+ for source builds. Docker source builds include their own toolchain.

```bash
git clone https://github.com/solacese/schema-validation-micro-integration.git
cd schema-validation-micro-integration
./scripts/verify.sh
./scripts/install.sh
```

CI checks Java 17, 21, and 25, shell/Python helpers, Compose configuration, and the Docker build. Opt into live tests against a disposable registry and a running MI using the default demo topic space:

```bash
MI_REGISTRY_TEST_URL=http://localhost:18081/apis/registry/v3 \
MI_BROKER_TEST_URL=tcp://localhost:55554 ./scripts/verify.sh
```

Live tests create a unique `mi-compat-*` group and verify valid/invalid round trips for every supported format. SDKPerf load and HA scenarios are separate from these functional checks; production throughput and delivery guarantees require workload qualification.

## Documentation and releases

- [Configuration, routing, caching, HA, and performance](docs/CONFIGURATION.md)
- [Installation and operations guide](docs/Schema-Validation-MI-Installation-Guide.docx)
- [Release history](CHANGELOG.md) and [release packaging](docs/RELEASING.md)
- [GitHub Releases](https://github.com/solacese/schema-validation-micro-integration/releases) — installation archives, executable JAR, SHA-256 checksums, and dependency SBOMs
- [Container package](https://github.com/solacese/schema-validation-micro-integration/pkgs/container/schema-validation-micro-integration)

The target is the **1.1.2 GA distribution**. Its verified backend currently reports `1.1.1` from `/system/info`; preserve the original archive/checksum to identify the package. The internal `3.0.7-SNAPSHOT` runner name is not the product version. Runtime files, credentials, and downloaded vendor packages are excluded from Git and release bundles. Existing registry databases must follow the vendor's upgrade/backup procedure.
