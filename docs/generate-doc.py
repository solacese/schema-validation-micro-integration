#!/usr/bin/env python3
"""Generate the concise installation and operations guide. Requires python-docx."""
from pathlib import Path
from docx import Document
from docx.shared import Inches, Pt, RGBColor
from docx.oxml import OxmlElement
from docx.oxml.ns import qn

OUTPUT = Path(__file__).with_name('Schema-Validation-MI-Installation-Guide.docx')
doc = Document()
section = doc.sections[0]
section.top_margin = section.bottom_margin = Inches(0.7)
section.left_margin = section.right_margin = Inches(0.75)
for name in ['Normal', 'Title', 'Subtitle', 'Heading 1', 'Heading 2']:
    style = doc.styles[name]
    style.font.name = 'Calibri'
    style.font.color.rgb = RGBColor(0, 0, 0)
doc.styles['Normal'].font.size = Pt(10.5)
doc.styles['Normal'].paragraph_format.space_after = Pt(6)
for name in ['Heading 1', 'Heading 2']:
    doc.styles[name].paragraph_format.space_before = Pt(10)
    doc.styles[name].paragraph_format.space_after = Pt(5)
# Word's default Title style can carry a blue bottom border.
for style in doc.styles:
    if style.element.pPr is not None:
        for border in list(style.element.pPr.findall(qn('w:pBdr'))):
            style.element.pPr.remove(border)
doc.styles['Heading 1'].font.size = Pt(18)
doc.styles['Heading 2'].font.size = Pt(12)


def text(value):
    doc.add_paragraph(value)


def heading(value):
    doc.add_heading(value, level=2)


def code(value):
    for line in value.strip().splitlines():
        p = doc.add_paragraph()
        p.paragraph_format.space_after = Pt(1)
        run = p.add_run(line)
        run.font.name = 'DejaVu Sans Mono'
        run.font.size = Pt(8)


def table(headers, rows):
    t = doc.add_table(rows=1, cols=len(headers))
    t.style = 'Light Shading Accent 1'
    for cell, value in zip(t.rows[0].cells, headers):
        cell.text = value
    repeat = OxmlElement('w:tblHeader')
    t.rows[0]._tr.get_or_add_trPr().append(repeat)
    for values in rows:
        for cell, value in zip(t.add_row().cells, values):
            cell.text = value
    for row in t.rows:
        for cell in row.cells:
            for p in cell.paragraphs:
                p.paragraph_format.space_after = Pt(4)
                for r in p.runs:
                    r.font.size = Pt(9)


def page(title):
    doc.add_page_break()
    doc.add_heading(title, level=1)


doc.add_paragraph('Schema Validation Micro Integration', style='Title')
doc.add_paragraph('Installation and operations guide', style='Subtitle')
text('Updated 3 November 2026 | Installation target: Solace Schema Registry 1.1.2 GA')
text('Validate Solace message payloads against JSON Schema, Avro, Protobuf, and XML Schema artifacts. '
     'The integration resolves schemas from message properties or topic mappings, validates the payload, '
     'and republishes it with validation metadata. The same image supports a local demo or existing brokers and registries.')
heading('Install the complete local stack')
text('You need Docker with Compose v2 or later, Python 3.9 or later, and curl. Java 17 and Maven are included '
     'in the container build; no host Java installation is required. Start Docker Desktop and sign in if your organization requires it.')
text('1. Download schema-registry-v1.1.2.tar.gz and its .md5 file from the Solace Products portal. '
     'Leave them compressed in your Downloads folder. Solace portal access is required.')
text('2. From the repository directory, run:')
code('./scripts/install.sh')
text('Setup prepares the release runtime, starts PostgreSQL, the embedded identity provider and registry API, '
     'starts a local broker, registers the sample schemas, builds the integration, and waits for healthy services. '
     'Re-running setup reuses the prepared files and existing schemas. Initial setup needs internet access for images and dependencies.')
text('For an archive elsewhere:')
code('SCHEMA_REGISTRY_DIST=/path/to/schema-registry-v1.1.2.tar.gz ./scripts/install.sh')
table(['Service', 'Local address'], [
    ['Integration health', 'http://localhost:8088/actuator/health'],
    ['Schema Registry API', 'http://localhost:18081/apis/registry/v3'],
    ['Broker management', 'http://localhost:8080 (admin / admin)'],
])
text('The local registry runs Java and Node application files from the licensed package on Apple Silicon or x86. '
     'This API stack does not include the registry web console.')

page('Validate all four schema formats')
table(['Registry type', 'Payload', 'Dependencies'], [
    ['JSON', 'UTF-8 JSON', 'Registry references resolve $ref names'],
    ['XML or XSD', 'XML against XSD 1.0', 'Registry-backed include and import'],
    ['AVRO', 'One raw binary Avro datum', 'Referenced named types'],
    ['PROTOBUF', 'Raw binary Protobuf message', 'Reference names match import paths'],
])
text('JSON uses the explicit $schema dialect, or 2020-12 when absent. XML external DTDs and remote schemas are blocked; '
     'register dependencies as registry references. Protobuf validates wire decodability: proto3 permits missing scalar '
     'fields and unknown fields. Avro container files and serializer-specific envelopes, such as a Confluent '
     'magic-byte/schema-ID prefix, must be decoded before ingress.')
heading('Select the schema using message properties')
code('schema_group = manufacturing-iot\nschema_name = pub-files\nschema_version = 1.0.0')
text('The version is optional; without it the most recently created enabled version is selected. '
     'The integration discovers the format from registry metadata. These properties take priority over topic mappings. '
     'The ingress queue must subscribe to the publisher topic.')
heading('Register your own schemas')
text('The helper infers the artifact type from .json, .avsc, .xsd, or .proto. Use --type to override. '
     'Set SCHEMA_REGISTRY_BASE_URL, SCHEMA_REGISTRY_USERNAME, and SCHEMA_REGISTRY_PASSWORD in the host shell for a remote registry. '
     'Registration requires a developer account; the integration itself can use a readonly account.')
code('python3 scripts/register-schema.py sensor.xsd --artifact sensor\n'
     'python3 scripts/register-schema.py reading.avsc --artifact reading\n'
     'python3 scripts/register-schema.py reading.proto --artifact reading --references refs.json')
text('Optional flags: --group (default: default), --version (default: 1.0.0), and --references. '
     'The references file contains a v3 JSON array of groupId, artifactId, version, and name objects. '
     'Register dependencies first and create a custom group before adding its first artifact. Duplicate versions fail; choose a new version.')
text('For Protobuf, select the root message using // @root fully.qualified.MessageName on the first nonempty schema line. '
     'Otherwise the first declared message is selected. Reference names must match the import statements, including directory paths.')
heading('Try the manufacturing demo')
code('./scripts/demo-publish.sh pub-files valid\n./scripts/demo-publish.sh pub-files invalid\n./scripts/demo-publish.sh all')
text('The sample JSON schemas and payloads are in schemas/json/mqtt-envelope and samples/json. '
     'The checksum rule checks the declared hash format; it does not retrieve files and recompute their contents.')

page('Connect existing services and configure routing')
heading('Install only the integration')
code('cp .env.example .env\n'
     '# Edit broker and registry variables in .env\n'
     'docker compose -f docker-compose.local.yml up -d --build\n'
     'curl --fail http://localhost:8088/actuator/health')
text('Set SOLACE_INPUT_BROKER, SOLACE_INPUT_VPN, SOLACE_INPUT_USERNAME, and SOLACE_INPUT_PASSWORD. '
     'Set the corresponding SOLACE_OUTPUT variables for the target broker. Use a registry URL ending in /apis/registry/v3. '
     'From Docker, use host.docker.internal to reach a registry running on this Mac.')
text('The default subscriptions cover the manufacturing demo. Mount optional overrides in config/application.yml '
     'and configure app.ingress.topic-subscriptions for your own topic space. Publishers can declare schemas in properties, '
     'or app.mappings can select schemas from topic patterns.')
heading('Configure topic mappings when needed')
code('app:\n'
     '  ingress:\n'
     '    topic-subscriptions: ["sensors/>"]\n'
     '  mappings:\n'
     '    - id: sensor-reading\n'
     '      topic-pattern: sensors/{site}/{device}\n'
     '      schema-group: default\n'
     '      artifact-id: sensor\n'
     '      artifact-type: XSD\n'
     '      version-strategy:\n'
     '        type: LATEST_ENABLED')
text('Version strategies are LATEST_ENABLED, FIXED, TOPIC_CAPTURE, and BRANCH. '
     'The README describes their syntax, topic captures, and cache configuration.')
heading('Route by validation outcome')
text('By default, output uses the original topic. User property mi_validation_status is VALIDATED, REJECT, or PROCESSING_ERROR. '
     'The integration skips messages already carrying a validation status to prevent republish loops.')
text('For separate output topics, including MQTT 3.1.1 consumers without user properties, set:')
code('VALIDATED_TOPIC_TEMPLATE=validated/{originalTopic}\n'
     'REJECT_TOPIC_TEMPLATE=rejected/{originalTopic}\n'
     'PROCESSING_ERROR_TOPIC_TEMPLATE=errors/{originalTopic}')
text('These prefixes sit outside the example ingress subscriptions. Templates also support {outcome}, {schemaVersion}, '
     '{mappingId}, and named captures. Configure the outcome vocabulary through OUTCOME_LABEL_VALIDATED, '
     'OUTCOME_LABEL_REJECT, and OUTCOME_LABEL_PROCESSING_ERROR. Ensure output topics do not feed another validating pipeline unintentionally.')

page('Operate and scale the integration')
heading('Health and observability')
code('curl --fail http://localhost:8088/actuator/health\n'
     'curl --fail http://localhost:8088/actuator/metrics\n'
     'docker logs --tail 100 schema-validation-micro-integration')
text('A healthy instance reports status UP. Registry lookup failures are surfaced by the health indicator. '
     'Message outcomes include schema coordinates, format, timestamp, failure category, failure reason, original topic, mapping ID, '
     'and trace ID as user properties. The input payload is preserved.')
heading('Limits and caching')
table(['Variable', 'Default', 'Purpose'], [
    ['CONSUMER_CONCURRENCY', '8', 'Parallel queue consumers'],
    ['MAX_PAYLOAD_BYTES', '1048576', 'Maximum input payload size'],
    ['VALIDATION_TIMEOUT', '5s', 'Time limit for payload validation'],
    ['SCHEMA_REGISTRY_REQUEST_TIMEOUT', '3s', 'HTTP request timeout'],
    ['PARTITION_COUNT', '0', 'Use a pre-provisioned partitioned queue when positive'],
])
text('Topic resolution, registry lookups, schema bundles, and compiled validators are cached. '
     'Compose uses a longer registry lookup TTL for the demo. Schema changes and state changes may remain cached until TTL expiry; '
     'shorten TTLs or restart the integration when immediate freshness is required. Size concurrency, resources, and TTLs using workload measurements.')
heading('Run three instances')
code('docker compose -f docker-compose.local.yml stop\n'
     'docker compose -f docker-compose.ha.yml up -d')
text('The three instances share a nonexclusive queue and expose health on ports 8088, 8089, and 8090. '
     'Override MI_1_HOST_PORT, MI_2_HOST_PORT, and MI_3_HOST_PORT if required. '
     'Stop the HA instances before resuming the single-instance stack.')
text('Partitioned queues must be provisioned separately through the supplied SEMP helper before starting consumers. '
     'Changing partition count can require recreation and lose queued messages; never use the helper to resize a live queue without a deliberate migration.')
heading('Stop the local stack')
code('./scripts/demo-teardown.sh')
text('Teardown stops and removes demo service containers, preserves registry data volumes, and leaves the shared network. '
     'Back up an existing registry using the release package instructions before upgrading it. This project does not automatically migrate or roll back an existing registry database.')

page('Verify an installation and maintain the project')
heading('Build and automated checks')
text('For development outside Docker, use Java 17 or later and Maven 3.9 or later. Verify that mvn -version shows the intended JDK. '
     'The verification command checks shell syntax, archive extraction, checksums, Spring application startup, resolution, routing, and validators.')
code('./scripts/verify.sh')
text('CI runs Java 17, 21, and 25 and builds the Docker image. Dependencies are managed using the Solace MDK 4.3.11 platform '
     'and Spring Boot 4.1.2. The custom validator uses the Solace Spring Cloud Stream binder directly; the stock connector workflow runtime '
     'is intentionally absent because it owns the same processing channels.')
heading('Run live registry and broker checks')
text('Run against a disposable registry. The tests create unique mi-compat groups and leave them for inspection. '
     'Set MI_REGISTRY_TEST_USERNAME and MI_REGISTRY_TEST_PASSWORD for credentials other than the local developer defaults.')
code('MI_REGISTRY_TEST_URL=http://localhost:18081/apis/registry/v3 \\\n'
     'MI_BROKER_TEST_URL=tcp://localhost:55554 ./scripts/verify.sh')
text('With the broker URL set, tests publish valid and invalid payloads for JSON, XML, Avro, and Protobuf through the running integration '
     'and verify returned status and format metadata. Optional broker settings are MI_BROKER_TEST_VPN, MI_BROKER_TEST_USERNAME, and '
     'MI_BROKER_TEST_PASSWORD. The running integration must use the same registry and broker and subscribe to the default demo topic space.')
heading('Performance and delivery guarantees')
text('The performance directory contains SDKPerf throughput, backpressure, and HA scenarios. SDKPerf JCSMP is a separate download. '
     'These are workload experiments; this guide makes no production throughput, global ordering, exactly-once, or zero-loss claim. '
     'A durable queue and multiple consumers provide recovery mechanisms, but delivery behavior must be qualified under failure and load for the intended deployment.')
heading('Identify the registry release correctly')
text('Installation targets schema-registry-v1.1.2.tar.gz from the 1.1.2 GA portal. The verified package currently reports version 1.1.1 '
     'from /system/info. Its internal runner is named with 3.0.7-SNAPSHOT; that name is not the Solace product release. '
     'Preserve the downloaded archive and checksum when identifying this installation.')
text('The runtime is prepared under .runtime/schema-registry/1.1.2 and excluded from Git and the Docker build context. '
     'Use SCHEMA_REGISTRY_DIST for an alternate archive path and SCHEMA_REGISTRY_RUNTIME_DIR for an alternate prepared directory.')
heading('Sources and further configuration')
text('The repository README is the detailed configuration reference. Release downloads: '
     'https://products.solace.com/products/1.1.2GA/Schema_Registry/solace-schema-registry/')
text('Official deployment and backup guidance: '
     'https://docs.solace.com/Schema-Registry/deploying-schema-registry-docker.htm')

doc.core_properties.title = 'Schema Validation Micro Integration Installation and Operations Guide'
doc.core_properties.subject = 'Solace Schema Registry 1.1.2 GA'
doc.core_properties.author = 'Solace'
doc.save(OUTPUT)
print(OUTPUT)
