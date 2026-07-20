# Performance Tests

SDKPerf-based performance tests for the Schema Validation Micro-Integration.

## Prerequisites

- Demo stack running: `./scripts/demo-setup.sh`
- Java 17+ and Python 3 on the host
- **SDKPerf JCSMP** -- not included in the repo, download from [Solace](https://solace.com/downloads/#sdkperf):

```bash
./performance/prepare-sdkperf.sh   # Shows download instructions
```

Extract to `performance/sdkperf-jcsmp-10.29.1/` so that `performance/sdkperf-jcsmp-10.29.1/sdkperf_java.sh` exists.

## Quick Start

```bash
./performance/run-all.sh
```

Results saved to `.runtime/performance/<timestamp>/performance-report.log`.

## Tests

| Script | What it measures |
|--------|-----------------|
| `test-throughput.sh` | Burst throughput at max rate (1k and 5k messages) |
| `test-sustained-rate.sh` | Sustained publish rate (100, 500, 1000 msg/sec for 30s) |
| `test-stress-mixed-validity.sh` | 99% valid / 1% invalid at scale (30k messages) |
| `test-backpressure.sh` | 50k message flood with 6 publishers |

### HA Tests (require `docker-compose.ha.yml` running)

| Script | What it measures |
|--------|-----------------|
| `test-ha-distribution.sh` | Load balance across 3 MI instances |
| `test-ha-failover.sh` | Kill one instance mid-flight, verify zero loss |
| `test-ha-rolling-restart.sh` | Restart each instance during load |

## Payloads

All tests use manufacturing IoT payloads (`samples/json/acme-pub-*.json`) published to the
the IoT topic space with schema metadata as user properties. The old watch-demo payloads
have been removed.
