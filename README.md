# Schema Validation Micro-Integration

A Solace MDK micro-integration that validates IoT messages against JSON schemas from Solace Schema Registry. Messages are republished on the **same topic** with validation outcome and schema metadata carried as **message user properties**.

Built for manufacturing IoT MQTT flows with dual-broker support (read from one VPN, write to another).

## Quick Start

**Prerequisites:** Docker, Maven, Java 17+

```bash
./scripts/demo-setup.sh
```

This starts everything in one command:

| Service             | URL                              |
|---------------------|----------------------------------|
| Schema Registry     | http://localhost:18081            |
| Solace Broker       | http://localhost:8080 (admin/admin) |
| Micro-Integration   | http://localhost:8088             |

## Try It

```bash
# Terminal 1 — watch output in real time
./scripts/demo-live.sh

# Terminal 2 — publish test messages
./scripts/demo-publish.sh pub-files valid       # validated
./scripts/demo-publish.sh pub-files invalid     # rejected (missing message_id)
./scripts/demo-publish.sh bad-checksum          # rejected (bad sha-256)
./scripts/demo-publish.sh all                   # all 12 scenarios
```

Run `./scripts/demo-publish.sh` with no args to see all available scenarios.

### Stress Test

Send sustained load and watch throughput in real time:

```bash
# Terminal 1 — live throughput monitor (one line per second)
./scripts/demo-monitor.sh

# Terminal 2 — stress test: 1000 msg/sec for 60 seconds
./scripts/demo-stress.sh              # defaults: 1000 msg/sec, 60s
./scripts/demo-stress.sh 2000 30      # 2000 msg/sec for 30s
```

`demo-stress.sh` includes a built-in monitor. Use `demo-monitor.sh` separately when running your own publishers or the SDKPerf performance suite.

## How It Works

```
                              ┌──────────────────────────┐
  Publishers ──> Topics ──>   │  Queue (durable)         │
                              │  8 consumer threads       │
                              └──────────┬───────────────┘
                                         │
                              ┌──────────▼───────────────┐
                              │  1. Resolve schema       │
                              │  2. Fetch from registry  │
                              │  3. Validate payload     │
                              │  4. Republish on same    │
                              │     topic + metadata     │
                              └──────────────────────────┘
```

By default the output message is published on the **same topic** as the input, and the validation result is conveyed entirely via user properties. When the output topic must differ from the input topic — or must itself carry the result (see [Output Topic Routing](#output-topic-routing)) — the routing templates can rewrite the topic per outcome.

The validation result is conveyed via user properties:

| User Property            | Example                          |
|--------------------------|----------------------------------|
| `mi_validation_status`   | `VALIDATED`, `REJECT`, `PROCESSING_ERROR` |
| `mi_schema_group`        | `manufacturing-iot`              |
| `mi_schema_artifact`     | `pub-files`                      |
| `mi_schema_version`      | `1`                              |
| `mi_schema_type`         | `JSON`                           |
| `mi_validation_timestamp`| `2026-04-09T10:30:00Z`           |
| `mi_failure_category`    | `NONE`, `VALIDATION`, `MAPPING`, `REGISTRY` |
| `mi_failure_reason`      | _(empty on success)_             |
| `mi_original_topic`      | `Acme/SA/PMNO-001/.../PUB_FILES` |
| `mi_mapping_id`          | `acme-datascience-eqt-pub-files` |
| `mi_trace_id`            | _(UUID or propagated)_           |

### Output Topic Routing

Each validation outcome has its own configurable output-topic template, so the publish topic can differ from the consumed topic and can encode the result directly in the topic. This is essential for **single-broker deployments** and for clients on protocols without user-property/header support (e.g. **MQTT 3.1.1**), where consumers must subscribe based on the validation result rather than a message property.

Templates support these placeholders:

| Placeholder        | Value                                                        |
|--------------------|--------------------------------------------------------------|
| `{originalTopic}`  | The consumed topic, verbatim                                 |
| `{outcome}`        | Configurable outcome label (`valid` / `invalid` / `error`)   |
| `{schemaVersion}`  | Resolved schema version                                      |
| `{mappingId}`      | Id of the resolved topic mapping                             |
| `{<capture>}`      | Any named capture group from the matched topic pattern       |

Configure per-outcome templates and the labels substituted for `{outcome}`:

```yaml
app:
  routing:
    validated-topic-template: "{originalTopic}/{outcome}"
    reject-topic-template: "{originalTopic}/{outcome}"
    processing-error-topic-template: "{originalTopic}/{outcome}"
    outcome-labels:
      validated: valid       # or VALID / Valid / V
      reject: invalid        # or INVALID / Invalid / I
      processing-error: error # or ERROR / Error / E
```

Examples (input → output):

```
acme/site1/line2/motor3/temperature4/raw/degrees  ->  acme/site1/line2/motor3/temperature4/valid/degrees
Acme/Site1/Line2/Motor3/Temperature4              ->  Acme/Site1/Line2/Motor3/Temperature4/Invalid
```

The mid-topic example uses a topic pattern with captures, e.g. `acme/{site}/{line}/{motor}/{sensor}/raw/{unit}` matched on ingress, then `acme/{site}/{line}/{motor}/{sensor}/{outcome}/{unit}` as the template. All three templates default to `{originalTopic}` (same-topic republish) for backward compatibility.

Environment-variable overrides: `VALIDATED_TOPIC_TEMPLATE`, `REJECT_TOPIC_TEMPLATE`, `PROCESSING_ERROR_TOPIC_TEMPLATE`, `OUTCOME_LABEL_VALIDATED`, `OUTCOME_LABEL_REJECT`, `OUTCOME_LABEL_PROCESSING_ERROR`.

### Schema Resolution

Schemas are resolved in priority order:

1. **Header-based** (publisher-declared) -- set `schema_group`, `schema_name`, and optionally `schema_version` as Solace user properties on the message
2. **Topic-based** (fallback) -- the message topic is matched against patterns configured in `application.yml`

### Version Strategies

| Strategy         | Description                                      |
|------------------|--------------------------------------------------|
| `LATEST_ENABLED` | Latest enabled version in the registry (default) |
| `TOPIC_CAPTURE`  | Extracted from a topic capture group              |
| `BRANCH`         | Registry branch lookup from topic capture         |
| `FIXED`          | Hardcoded version in config                       |

### Caching

Four-level cache prevents repeated registry calls:

| Cache              | Default TTL | Max Entries |
|--------------------|-------------|-------------|
| Topic resolution   | 15 min      | 5,000       |
| Registry metadata  | 60 min      | 1,000       |
| Schema bundles     | 60 min      | 1,000       |
| Compiled validators| 60 min      | 1,000       |

## Schemas

Four JSON Schema artifacts in the `manufacturing-iot` group (JSON Schema 2020-12):

| Artifact          | Purpose                                 | Dependencies              |
|-------------------|-----------------------------------------|---------------------------|
| `metadata`        | Timestamp, message_id, seq, topic       | --                        |
| `context-generic` | Flexible business context               | --                        |
| `pub-files`       | File transfers with checksum validation | metadata, context-generic |
| `pub-table`       | Tabular sensor data (rows/columns)      | metadata, context-generic |

Source files in `schemas/json/mqtt-envelope/`. Sample payloads in `samples/json/`.

## Configuration

### Environment

```bash
cp .env.example .env   # local broker defaults -- edit for Solace Cloud
```

Key variables:

| Variable                 | Default                   | Description                      |
|--------------------------|---------------------------|----------------------------------|
| `SOLACE_INPUT_BROKER`    | `tcp://solace-broker:55555` | Source broker connection        |
| `SOLACE_OUTPUT_BROKER`   | _(same as input)_         | Target broker (dual-broker mode) |
| `SCHEMA_REGISTRY_BASE_URL` | `http://...:8080/apis/registry/v3` | Schema Registry endpoint |
| `CONSUMER_CONCURRENCY`   | `8`                       | Parallel consumer threads        |
| `MAX_PAYLOAD_BYTES`      | `1048576`                 | Max message size (1 MB)          |
| `VALIDATION_TIMEOUT`     | `5s`                      | Timeout per validation           |
| `PARTITION_COUNT`        | `0`                       | Partitioned queue (0 = disabled) |

### Docker Compose Files

| File                                | Purpose                                   |
|-------------------------------------|-------------------------------------------|
| `docker-compose.broker.yml`         | Local Solace PubSub+ broker               |
| `docker-compose.registry-native.yml`| Schema Registry (Postgres + IDP + backend)|
| `docker-compose.local.yml`          | MI -- single instance                     |
| `docker-compose.ha.yml`             | MI -- 3-instance HA cluster               |

### Hardened Image

The Dockerfile is ready for a Docker Hardened Image runtime. Local development uses the public Temurin JRE by default; build with DHI after authenticating the Docker client to `dhi.io`:

```bash
docker build \
  --build-arg RUNTIME_IMAGE=dhi.io/eclipse-temurin:17 \
  -t schema-validation-micro-integration:dhi .
```

The runtime runs as UID/GID `65532`, uses cgroup-aware JVM sizing, includes a shell-free Java healthcheck, and the compose files run with a read-only filesystem, dropped capabilities, `no-new-privileges`, `/tmp` tmpfs, process/memory/CPU limits, and restart policies.

### Topic Subscriptions

The MI subscribes to the manufacturing IoT topic space:

```
{ENTERPRISE}/{SITE}/{PMNO}/{CATEGORY}/{PROTOCOL}/{SOURCE}/{MESSAGE}
```

16 mappings cover all combinations of Acme/Apex, DATASCIENCE/SHOPFLOOR, FromEqt/FromSI, PUB_FILES/PUB_TABLE. See `application.yml` for the full list.

## Performance Testing

```bash
./performance/run-all.sh
```

| Test                          | What it measures                           |
|-------------------------------|--------------------------------------------|
| `test-throughput.sh`          | Burst throughput (1k, 5k messages)         |
| `test-sustained-rate.sh`     | Steady state (100, 500, 1000 msg/sec)      |
| `test-stress-mixed-validity.sh` | 99% valid / 1% invalid at 30k messages  |
| `test-backpressure.sh`       | 50k message flood with 6 publishers        |

### HA Tests

```bash
docker compose -f docker-compose.ha.yml up -d
./performance/test-ha-distribution.sh      # load balance across 3 instances
./performance/test-ha-failover.sh          # kill one instance, verify zero loss
./performance/test-ha-rolling-restart.sh   # restart during load
```

Requires SDKPerf JCSMP -- see `performance/README.md`.
If a host port is already in use, override `MI_1_HOST_PORT`, `MI_2_HOST_PORT`, or `MI_3_HOST_PORT`; the container ports remain 8088, 8089, and 8090.

## Scripts

| Script                          | Purpose                                    |
|---------------------------------|--------------------------------------------|
| `demo-setup.sh`                 | Start everything, seed schemas, verify health |
| `demo-teardown.sh`              | Stop all containers                        |
| `demo-live.sh`                  | Live subscriber for MI output              |
| `demo-publish.sh`               | Publish test messages (12 scenarios)       |
| `demo-stress.sh`                | Stress test with built-in live monitor     |
| `demo-monitor.sh`               | Standalone throughput monitor (1 line/sec) |
| `register-schemas.sh`           | Seed manufacturing-iot schemas into registry |
| `provision-partitioned-queue.sh`| Create partitioned queue via SEMP          |

## Project Structure

```
src/main/java/com/solace/schemavalidator/
  config/          Configuration, ingress subscriptions, caches
  messaging/       Validation orchestration, routing, publishing
  resolution/      Topic pattern matching, version strategies
  registry/        HTTP client, caching wrapper, schema bundle loading
  validation/      JSON / Avro / Protobuf / XSD validator factories
  observability/   Metrics, health indicator, trace ID propagation

schemas/           JSON Schema artifacts (manufacturing-iot group)
samples/           Test payloads (valid + invalid per scenario)
performance/       SDKPerf-based performance and HA tests
scripts/           Setup, demo, and provisioning scripts
```

## Teardown

```bash
./scripts/demo-teardown.sh
```
