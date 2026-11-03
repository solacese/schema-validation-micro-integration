# Changelog

## [1.0.0] - 2026-11-03

Initial packaged release of the Schema Validation Micro-Integration.

- Target the Solace Schema Registry 1.1.2 GA distribution, including its current archive layout and Docker layer whiteouts.
- Validate JSON Schema, XML/XSD, binary Avro, and binary Protobuf; resolve schema references and discover formats from registry metadata.
- Add one-command local installation and an existing-services mode. Release archives include prebuilt Java artifacts.
- Fix application startup, shared Docker networking, readiness failures, pagination of registry versions, cyclic references, XML external-resource access, Protobuf imports/required fields, and concurrent Avro validation.
- Update to the MDK 4.3.11 dependency platform, Spring Boot 4.1.2, Jackson 3, and current validation libraries.
- Add application startup, format, installer, and opt-in live registry/broker tests; verify local and three-instance operation.
- Publish installation archives, an executable JAR, SHA-256 checksums, CycloneDX dependency inventories, and an amd64/arm64 GHCR image.

The Solace 1.1.2 archive currently reports backend version 1.1.1 from `/system/info`. The original licensed registry distribution is not included. Load/failure tests do not establish production throughput, global ordering, or exactly-once delivery.
