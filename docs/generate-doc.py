#!/usr/bin/env python3
"""Build the versioned installation and setup manual. Requires python-docx."""
from pathlib import Path
import xml.etree.ElementTree as ET
from docx import Document
from docx.shared import Inches, Pt, RGBColor
from docx.enum.table import WD_CELL_VERTICAL_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn

ROOT = Path(__file__).resolve().parent.parent
VERSION = ET.parse(ROOT / 'pom.xml').getroot().find('{http://maven.apache.org/POM/4.0.0}version').text
OUTPUT = ROOT / 'docs/Schema-Validation-MI-Installation-Guide.docx'
REPO = 'https://github.com/solacese/schema-validation-micro-integration'
REGISTRY = 'https://products.solace.com/products/1.1.2GA/Schema_Registry/solace-schema-registry/'
doc = Document()
section = doc.sections[0]
section.top_margin = section.bottom_margin = Inches(0.65)
section.left_margin = section.right_margin = Inches(0.75)
section.footer_distance = Inches(0.28)
for name in ['Normal', 'Title', 'Subtitle', 'Heading 1', 'Heading 2']:
    style = doc.styles[name]
    style.font.name = 'Calibri'
    style.font.color.rgb = RGBColor(0, 0, 0)
normal = doc.styles['Normal']
normal.font.size = Pt(10.5)
normal.paragraph_format.space_after = Pt(6)
normal.paragraph_format.line_spacing = 1.05
doc.styles['Title'].font.size = Pt(26)
doc.styles['Subtitle'].font.size = Pt(14)
for name, size in [('Heading 1', 19), ('Heading 2', 12)]:
    style = doc.styles[name]
    style.font.size = Pt(size)
    style.paragraph_format.space_before = Pt(10)
    style.paragraph_format.space_after = Pt(5)
for style in doc.styles:
    if style.element.pPr is not None:
        for border in list(style.element.pPr.findall(qn('w:pBdr'))):
            style.element.pPr.remove(border)
footer = section.footer.paragraphs[0]
footer.alignment = WD_ALIGN_PARAGRAPH.RIGHT
footer.add_run(f'MI {VERSION} | ')
field = OxmlElement('w:fldSimple')
field.set(qn('w:instr'), 'PAGE')
footer._p.append(field)
for run in footer.runs:
    run.font.size = Pt(8)


def text(value):
    return doc.add_paragraph(value)


def heading(value):
    doc.add_heading(value, level=2)


def step(number, title, detail=None):
    p = doc.add_paragraph()
    p.paragraph_format.keep_with_next = True
    p.add_run(f'{number}. {title}').bold = True
    if detail:
        text(detail)


def code(value):
    lines = value.strip('\n').splitlines()
    for index, line in enumerate(lines):
        p = doc.add_paragraph()
        p.paragraph_format.space_before = Pt(0)
        p.paragraph_format.space_after = Pt(0 if index < len(lines) - 1 else 6)
        p.paragraph_format.line_spacing = 1.0
        p.paragraph_format.keep_with_next = index < len(lines) - 1
        run = p.add_run(line)
        run.font.name = 'DejaVu Sans Mono'
        run.font.size = Pt(8)


def table(headers, rows, widths):
    t = doc.add_table(rows=1, cols=len(headers))
    t.autofit = False
    for column, width in zip(t.columns, widths):
        column.width = Inches(width)
    borders = OxmlElement('w:tblBorders')
    for edge in ['top', 'left', 'bottom', 'right', 'insideH', 'insideV']:
        item = OxmlElement('w:' + edge)
        for key, val in [('val', 'single'), ('sz', '4'), ('color', 'D9D9D9')]:
            item.set(qn('w:' + key), val)
        borders.append(item)
    t._tbl.tblPr.append(borders)
    for cell, value in zip(t.rows[0].cells, headers):
        cell.text = value
    repeat = OxmlElement('w:tblHeader')
    t.rows[0]._tr.get_or_add_trPr().append(repeat)
    for values in rows:
        for cell, value in zip(t.add_row().cells, values):
            cell.text = value
    for row_index, row in enumerate(t.rows):
        cant_split = OxmlElement('w:cantSplit')
        row._tr.get_or_add_trPr().append(cant_split)
        for column_index, cell in enumerate(row.cells):
            cell.width = Inches(widths[column_index])
            cell.vertical_alignment = WD_CELL_VERTICAL_ALIGNMENT.CENTER
            props = cell._tc.get_or_add_tcPr()
            shading = OxmlElement('w:shd')
            shading.set(qn('w:fill'), '243746' if row_index == 0 else ('F0F4F7' if row_index % 2 else 'FFFFFF'))
            props.append(shading)
            margins = OxmlElement('w:tcMar')
            for edge, value in [('top', '65'), ('bottom', '65'), ('left', '85'), ('right', '85')]:
                item = OxmlElement('w:' + edge)
                item.set(qn('w:w'), value)
                item.set(qn('w:type'), 'dxa')
                margins.append(item)
            props.append(margins)
            for p in cell.paragraphs:
                p.paragraph_format.space_after = Pt(0)
                p.paragraph_format.line_spacing = 1.0
                for run in p.runs:
                    run.font.size = Pt(9)
                    run.bold = row_index == 0
                    run.font.color.rgb = RGBColor.from_string('FFFFFF' if row_index == 0 else '000000')
    p = doc.add_paragraph()
    p.paragraph_format.space_after = Pt(0)
    p.paragraph_format.space_before = Pt(0)
    p.paragraph_format.line_spacing = Pt(3)
    p.add_run().font.size = Pt(3)


def page(title):
    doc.add_page_break()
    doc.add_heading(title, level=1)


# 1: An installation decision and a map, without a separate cover page.
doc.add_paragraph('Schema Validation Micro Integration', style='Title')
doc.add_paragraph('Installation and setup guide', style='Subtitle')
text(f'Integration {VERSION} | Schema Registry 1.1.2 GA | Updated 3 November 2026')
text('This guide takes you from the release download to a working validation flow. '
     'For the fastest start, use the public release bundle and the local stack. '
     'If you already have a broker and registry, follow the existing-services path instead.')
heading('Choose your installation path')
table(['Path', 'Use it when', 'Start here'], [
    ['Local stack', 'You want a complete working demonstration with a broker and registry.', 'Page 2'],
    ['Existing services', 'Your broker and Schema Registry are already available.', 'Page 4'],
    ['GHCR image', 'You deploy container images and have access to Solace GitHub Packages.', 'Page 8'],
    ['Java application', 'You want to run the executable JAR without Docker for the MI.', 'Page 8'],
], [1.25, 4.75, 1.0])
heading('What you need')
text('For Docker installation: Docker with Compose v2 or later, Python 3.9 or later, curl, '
     'and a Bash-compatible shell. The commands work in Bash or zsh on macOS/Linux; '
     'Windows users should run them in WSL2 with Docker integration enabled. '
     'A release bundle includes prebuilt Java artifacts, so no host Java or Maven is needed.')
text('For a source build: Git, Java 17 or later, and Maven 3.9 or later. '
     'For Java deployment: Java 17 or later and existing broker/registry services. '
     'GHCR access is optional; the release archives and JAR are public.')
heading('Keep the two product versions separate')
text(f'The integration version is {VERSION}. The separate licensed registry download is '
     'schema-registry-v1.1.2.tar.gz. The verified registry package currently reports 1.1.1 '
     'from /system/info; this does not mean the installer selected an older archive. '
     'The registry web console is not included in the local API stack.')
heading('Guide map')
text('2 Download and install  |  3 Verify the local demo  |  4 Connect existing services\n'
     '5 Configure your topics  |  6 Register JSON and XML  |  7 Register binary formats\n'
     '8 Deploy an image or JAR  |  9 Operate and scale  |  10 Troubleshoot and verify')

# 2: Copyable, verified public download flow.
page('Download and install the local stack')
step(1, 'Start Docker and check the tools')
code('docker ps\ndocker compose version\npython3 --version\ncurl --version')
text('docker ps must succeed. If Docker Desktop requires Solace organization sign-in, '
     'complete that sign-in before continuing. The local Compose configuration uses ports '
     '8088, 18081, 13000, 15432, 8080, 55554, 55443, 1883, 8883, 8000, 8443, 9000, and 9443; '
     'stop any conflicting local services or use the existing-services path.')
step(2, 'Download the registry distribution')
text('Sign in to Solace Products and download schema-registry-v1.1.2.tar.gz plus its .md5 file. '
     'Put both files in your Downloads folder and leave the archive compressed. '
     'The integration installer does not download this licensed package for you.')
text(REGISTRY)
step(3, 'Download and verify the integration release')
code(f'MI_VERSION={VERSION}\n'
     f'REPO={REPO}\n'
     'DOWNLOAD="$REPO/releases/download/v$MI_VERSION"\n'
     'ARCHIVE="schema-validation-micro-integration-$MI_VERSION.tar.gz"\n'
     'curl -fLO "$DOWNLOAD/$ARCHIVE"\n'
     'curl -fLO "$DOWNLOAD/SHA256SUMS"\n'
     'awk -v file="$ARCHIVE" \'$2 == file {print}\' SHA256SUMS | \\\n'
     '  shasum -a 256 -c -')
text('Expected: the archive filename followed by OK. On Linux, sha256sum -c - can replace '
     'shasum -a 256 -c -. Stop and download again if verification fails.')
step(4, 'Extract and install')
code('tar -xzf "$ARCHIVE"\n'
     'cd "schema-validation-micro-integration-$MI_VERSION"\n'
     './scripts/install.sh')
text('The installer creates .env if absent, verifies the adjacent registry MD5 when present, '
     'prepares the registry runtime, starts PostgreSQL/identity provider/API and the broker, '
     'registers four sample JSON schemas, and starts the MI. It waits for healthy services. '
     'The prebuilt release JARs skip the Maven Docker stage; initial public image downloads still need internet access.')
text('For another registry archive location, run:')
code('SCHEMA_REGISTRY_DIST=/path/to/schema-registry-v1.1.2.tar.gz \\\n'
     '  ./scripts/install.sh')

# 3: A success criterion, not just a process start.
page('Verify the local demo')
step(1, 'Confirm that the installation finished')
text('Continue after the installer prints Demo ready. Run:')
code('docker ps\ncurl --fail http://localhost:8088/actuator/health')
text('Expected: the MI container is healthy and the health response contains status UP. '
     'You should also see solace-broker, schema-registry-postgres, schema-registry-idp, '
     'and schema-registry-backend running.')
table(['Service', 'Host endpoint'], [
    ['MI health', 'http://localhost:8088/actuator/health'],
    ['MI metrics', 'http://localhost:8088/actuator/metrics'],
    ['Registry API', 'http://localhost:18081/apis/registry/v3'],
    ['Broker management', 'http://localhost:8080'],
    ['Broker SMF', 'tcp://localhost:55554'],
    ['Broker REST messaging', 'http://localhost:9000'],
], [1.65, 5.35])
step(2, 'Publish one valid and one invalid message')
code('./scripts/demo-publish.sh pub-files valid\n'
     './scripts/demo-publish.sh pub-files invalid\n'
     'docker logs --since 2m schema-validation-micro-integration 2>&1 | \\\n'
     '  grep -E "event=(validated|rejected)"')
text('Expected: event=validated for the first message and event=rejected for the second '
     '(the invalid sample lacks message_id). An HTTP 200 from REST publishing confirms '
     'broker acceptance; the MI log or output user properties confirm validation.')
step(3, 'Inspect the result or run all scenarios')
code('./scripts/demo-publish.sh all\n./scripts/demo-live.sh')
text('The optional live viewer downloads PrettyDump and requires a suitable host Java runtime. '
     'It subscribes to the demo topics and shows message properties. It may show the original '
     'publish as well as the result because output defaults to the same topic. '
     'Use mi_validation_status to identify the processed message.')
heading('Local demonstration credentials')
text('Broker management: admin / admin. Broker messaging: default user in default VPN, password default. '
     'Registry developer: sr-developer / devPassword. Registry readonly: sr-readonly / roPassword. '
     'These are local demonstration defaults; configure your own accounts for existing services. '
     'Use a developer account to register schemas and a readonly account for the running MI.')

# 4: Concrete dotenv plus the actual supported installer switch.
page('Connect existing broker and registry services')
text('Use the extracted release directory. This path starts only the MI and does not install '
     'or modify a remote broker/registry. The input client needs permission to create/bind the '
     'durable ingress queue and add its subscriptions; the output client needs publish permission.')
step(1, 'Create the connection configuration')
code('cp .env.example .env\nmkdir -p config')
text('Edit the following values in .env. Replace example hostnames and credentials before starting. '
     'Set both input and output values to the same service for one-broker operation.')
code('SOLACE_INPUT_BROKER=tcps://input.example.com:55443\n'
     'SOLACE_INPUT_VPN=source-vpn\n'
     'SOLACE_INPUT_USERNAME=mi-reader\n'
     "SOLACE_INPUT_PASSWORD='replace-with-input-password'\n"
     'SOLACE_OUTPUT_BROKER=tcps://output.example.com:55443\n'
     'SOLACE_OUTPUT_VPN=target-vpn\n'
     'SOLACE_OUTPUT_USERNAME=mi-publisher\n'
     "SOLACE_OUTPUT_PASSWORD='replace-with-output-password'\n"
     'SCHEMA_REGISTRY_BASE_URL=https://registry.example.com/apis/registry/v3\n'
     'SCHEMA_REGISTRY_USERNAME=sr-readonly\n'
     "SCHEMA_REGISTRY_PASSWORD='replace-with-registry-password'")
text('Use the SMF endpoint supplied by your broker, including the correct TLS port and '
     'trusted certificates. Do not replace a broker endpoint with its browser management URL. '
     'Quote shell-sensitive passwords in .env and keep this file out of Git.')
step(2, 'Use addresses that containers can reach')
table(['Location of service', 'Address from the MI container'], [
    ['Same local demo network', 'solace-broker:55555 and schema-registry-backend:8080'],
    ['On this Mac host', 'host.docker.internal with the exposed host port'],
    ['Remote service', 'A DNS name/IP reachable from Docker, with the configured TLS port'],
], [2.0, 5.0])
text('localhost inside the MI container refers to the MI container. For example, a registry on '
     'the Mac host at port 18081 uses http://host.docker.internal:18081/apis/registry/v3. '
     'The registry base URL must include /apis/registry/v3.')
step(3, 'Configure your topics and start')
text('Before starting, follow page 5 to create config/application.yml for your topic space.')
code('./scripts/install.sh --existing\n'
     'curl --fail http://localhost:8088/actuator/health')
text('The command builds the thin runtime image from bundled JARs and waits for healthy status. '
     'When connection settings change, re-run it so Compose recreates the MI with the new environment.')

# 5: Header and mapping selection with a runnable example.
page('Configure topics and schema selection')
text('Save overrides in config/application.yml relative to the extracted installation directory. '
     'Compose mounts this directory into the MI. The built-in subscriptions cover the manufacturing demo; '
     'replace them for your own topic space. Schema selection supports two approaches.')
heading('Approach A Publishers declare the schema')
code('app:\n'
     '  ingress:\n'
     '    topic-subscriptions:\n'
     '      - sensors/>')
text('Publish to a topic such as sensors/site1/device1 and set these string user properties:')
code('schema_group = default\nschema_name = sensor-json\nschema_version = 1.0.0')
text('The MI loads the artifact and discovers its format from the registry. The same subscription '
     'can therefore validate all four formats. The version is optional; omitting it chooses the '
     'most recently created enabled version. Both group and name are required for header selection.')
heading('Approach B Topic mappings choose the schema')
text('If publishers cannot supply schema properties, use this complete configuration instead. '
     'It validates every matching sensor topic against sensor-json. Register that artifact using page 6.')
code('app:\n'
     '  ingress:\n'
     '    topic-subscriptions: ["sensors/>"]\n'
     '  mappings:\n'
     '    - id: sensor-reading\n'
     '      topic-pattern: sensors/{site}/{device}\n'
     '      schema-group: default\n'
     '      artifact-id: sensor-json\n'
     '      artifact-type: JSON\n'
     '      version-strategy:\n'
     '        type: LATEST_ENABLED')
text('Use artifact-type JSON, AVRO, PROTOBUF, or XSD in topic mappings; the registry uses XML '
     'for XML Schema artifacts. Explicit publisher properties take priority over a matching mapping.')
heading('Choose a version strategy')
table(['Strategy', 'Configuration below version-strategy'], [
    ['LATEST_ENABLED', 'type: LATEST_ENABLED'],
    ['FIXED', 'type: FIXED plus value: "1.0.0"'],
    ['TOPIC_CAPTURE', 'type: TOPIC_CAPTURE plus capture: version; include {version} in topic-pattern'],
    ['BRANCH', 'type: BRANCH plus value: main, or capture: branch with {branch} in the pattern'],
], [1.65, 5.35])
text('After changing the mounted configuration, restart: '
     'docker compose -f docker-compose.local.yml restart schema-validation-micro-integration.')

# 6: Minimal JSON and XML examples plus the shared REST publication function.
page('Register and test JSON and XML schemas')
text('The following examples use the local registry. Run commands from the installation directory. '
     'For a remote registry, replace the URL and developer credentials. The registration helper '
     'reads the host shell environment; it does not automatically read .env.')
code('export SCHEMA_REGISTRY_BASE_URL=http://localhost:18081/apis/registry/v3\n'
     'export SCHEMA_REGISTRY_USERNAME=sr-developer\n'
     'export SCHEMA_REGISTRY_PASSWORD=devPassword')
heading('Create and register a JSON Schema')
code("cat > sensor.json <<'JSON'\n"
     '{"$schema":"https://json-schema.org/draft/2020-12/schema",\n'
     ' "type":"object","properties":{"value":{"type":"number"}},\n'
     ' "required":["value"],"additionalProperties":false}\n'
     'JSON\n'
     'python3 scripts/register-schema.py sensor.json \\\n'
     '  --artifact sensor-json --version 1.0.0\n'
     "printf '%s' '{\"value\":7}' > reading.json")
heading('Create and register an XML Schema')
code("cat > sensor.xsd <<'XML'\n"
     '<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">\n'
     '  <xs:element name="reading" type="xs:int"/>\n'
     '</xs:schema>\n'
     'XML\n'
     'python3 scripts/register-schema.py sensor.xsd \\\n'
     '  --artifact sensor-xml --version 1.0.0\n'
     "printf '%s' '<reading>7</reading>' > reading.xml")
heading('Publish either format through the local broker')
text('After a default local installation, define this function in the same terminal. '
     'It uses a topic already covered by the demo queue and selects the schema through user properties. '
     'If you changed subscriptions to sensors/>, change TOPIC below to sensors/site1/device1.')
code('TOPIC=Acme/Guide/Device/DATASCIENCE/RAW/FromEqt/CHECK\n'
     'publish_reading() {\n'
     '  curl --fail -u default:default \\\n'
     '    -H "Content-Type: application/octet-stream" \\\n'
     '    -H "Solace-Delivery-Mode: persistent" \\\n'
     '    -H "Solace-User-Property-schema_group: default" \\\n'
     '    -H "Solace-User-Property-schema_name: $1" \\\n'
     '    -H "Solace-User-Property-schema_version: 1.0.0" \\\n'
     '    --data-binary "@$2" "http://localhost:9000/TOPIC/$TOPIC"\n'
     '}\n'
     'publish_reading sensor-json reading.json\n'
     'publish_reading sensor-xml reading.xml')
text('Expected: event=validated in the MI log for each artifact. For an invalid JSON example, '
     'publish {"value":"bad"}; for XML, publish <reading>bad</reading>. The result should be REJECT.')

# 7: Small deterministic wire encodings so no SDK/compiler is required.
page('Register binary schemas and dependencies')
text('Continue in the same terminal as page 6, with the registration environment and publish_reading '
     'function defined. The helper infers AVRO from .avsc and PROTOBUF from .proto. '
     'These small examples create raw binary payloads directly; production publishers should use their format library.')
heading('Avro One integer record')
code("cat > reading.avsc <<'AVRO'\n"
     '{"type":"record","name":"Reading",\n'
     ' "fields":[{"name":"value","type":"int"}]}\n'
     'AVRO\n'
     'python3 scripts/register-schema.py reading.avsc \\\n'
     '  --artifact sensor-avro --version 1.0.0\n'
     "printf '\\016' > reading.avro.bin\n"
     'publish_reading sensor-avro reading.avro.bin')
text('The single byte encodes value 7 as an Avro integer. A truncated integer, for example '
     'a single byte written with printf \'\\200\', should be rejected. Use one raw Avro datum, '
     'not an Avro object container file or a serializer-specific header.')
heading('Protobuf One integer message')
code("cat > reading.proto <<'PROTO'\n"
     'syntax = "proto3";\n'
     'message Reading { int32 value = 1; }\n'
     'PROTO\n'
     'python3 scripts/register-schema.py reading.proto \\\n'
     '  --artifact sensor-proto --version 1.0.0\n'
     "printf '\\010\\007' > reading.proto.bin\n"
     'publish_reading sensor-proto reading.proto.bin')
text('The two bytes encode field 1 with value 7. The validator supports well-known Protobuf types, '
     'checks proto2 required fields, and permits proto3 missing scalar fields/unknown fields. '
     'To choose a root message explicitly, put // @root fully.qualified.MessageName on the first '
     'nonempty schema line; otherwise the first declared message is selected.')
heading('Register referenced schemas before their parent')
text('Pass --references refs.json when registering the parent. The file is a v3 JSON array; '
     'each entry contains groupId, artifactId, version, and name. For example:')
code('[{"groupId":"default","artifactId":"shared-types",\n'
     '  "version":"1.0.0","name":"shared/types.proto"}]')
text('For Protobuf, name must match the import path. For JSON, match the $ref URI. For XML, '
     'match the include/import schemaLocation. For Avro, match the referenced named type. '
     'The MI resolves these dependencies from the registry; XML external DTDs and remote schemas '
     'are blocked. JSON dialect comes from $schema, or defaults to 2020-12. XML uses XSD 1.0.')
text('Duplicate versions fail clearly. Register changed schema content under a new version rather '
     'than overwriting a previous version. Use --group to select a non-default group.')

# 8: Optional deployment paths are complete, with host vs container env made explicit.
page('Deploy a container image or Java application')
heading('Use the GHCR image')
text('The image is published for linux/amd64 and linux/arm64. Solace organization policy keeps '
     'GHCR packages private. Authenticate with GitHub Packages credentials that can read the package. '
     'The public release bundle is the easier option when GHCR access is unavailable.')
code('docker login ghcr.io\n'
     f'IMAGE=ghcr.io/solacese/schema-validation-micro-integration:{VERSION}\n'
     'docker pull "$IMAGE"\n'
     'mkdir -p config\n'
     'docker run -d --name schema-validation-mi-image \\\n'
     '  --env-file .env -p 8088:8088 \\\n'
     '  --add-host host.docker.internal:host-gateway \\\n'
     '  -v "$PWD/config:/app/config:ro" "$IMAGE"')
text('Use .env values reachable from this container as described on page 4. For the local demo '
     'service names, also attach --network schema-validation-mi_default. Stop any existing MI on '
     'port 8088 before running this command. Inspect with docker logs schema-validation-mi-image; '
     'stop/remove with docker stop and docker rm using that container name.')
heading('Run the release JAR on the host')
text('Use Java 17 or later. From the extracted release directory, export host-reachable values. '
     'This complete example connects to an already-running local demo broker and registry. '
     'Stop the Docker MI first so only the intended instance consumes the queue.')
code('export SOLACE_INPUT_BROKER=tcp://localhost:55554\n'
     'export SOLACE_INPUT_VPN=default\n'
     'export SOLACE_INPUT_USERNAME=default\n'
     'export SOLACE_INPUT_PASSWORD=default\n'
     'export SOLACE_OUTPUT_BROKER="$SOLACE_INPUT_BROKER"\n'
     'export SOLACE_OUTPUT_VPN=default\n'
     'export SOLACE_OUTPUT_USERNAME=default\n'
     'export SOLACE_OUTPUT_PASSWORD=default\n'
     'export SCHEMA_REGISTRY_BASE_URL=http://localhost:18081/apis/registry/v3\n'
     'export SCHEMA_REGISTRY_USERNAME=sr-readonly\n'
     'export SCHEMA_REGISTRY_PASSWORD=roPassword\n'
     'java -jar lib/schema-validation-micro-integration.jar \\\n'
     '  --spring.config.additional-location=optional:file:./config/')
text('The Java process does not load .env automatically. Connection settings must be exported '
     'or supplied as application configuration. Verify status UP at localhost:8088/actuator/health. '
     'Use Ctrl+C to stop the Java process.')
heading('Build from source when developing')
code(f'git clone {REPO}.git\n'
     'cd schema-validation-micro-integration\n'
     './scripts/verify.sh\n'
     './scripts/install.sh')
text('Host verification requires Java 17+ and Maven 3.9+. Set JAVA_HOME to the chosen JDK. '
     'A Docker source build includes Maven and Java; release-bundle installation uses prebuilt JARs.')

# 9: Operating instructions use Compose-compatible settings rather than new features.
page('Route results and operate the integration')
heading('Separate output topics by outcome')
text('By default, output uses the original topic and carries mi_validation_status as a user property. '
     'Messages already carrying that property are skipped to prevent local republish loops. '
     'For MQTT 3.1.1 consumers or separate validated/rejected streams, set these values in .env:')
code('VALIDATED_TOPIC_TEMPLATE=validated/{originalTopic}\n'
     'REJECT_TOPIC_TEMPLATE=rejected/{originalTopic}\n'
     'PROCESSING_ERROR_TOPIC_TEMPLATE=errors/{originalTopic}')
text('These prefixes are outside the example ingress subscriptions. Templates also support '
     '{outcome}, {schemaVersion}, {mappingId}, and named topic captures. Keep error templates usable '
     'when no mapping is resolved; {originalTopic} works in that case. Re-run ./scripts/install.sh --existing '
     'after environment changes so the container is recreated.')
heading('Tune limits and schema freshness')
table(['Setting', 'Default in supplied Compose', 'Effect'], [
    ['CONSUMER_CONCURRENCY', '8', 'Parallel consumers'],
    ['MAX_PAYLOAD_BYTES', '1048576', 'Maximum payload size in bytes'],
    ['VALIDATION_TIMEOUT', '5s', 'Payload validation timeout'],
    ['SCHEMA_REGISTRY_REQUEST_TIMEOUT', '1500ms', 'Timeout for each registry HTTP request'],
    ['REGISTRY_LOOKUP_TTL', '120m', 'Cache of versions and registry metadata'],
], [2.9, 1.65, 2.45])
text('Place overrides in .env. Latest-version lookups and version-state changes can remain cached '
     'until TTL expiry. Use a shorter REGISTRY_LOOKUP_TTL or restart when immediate refresh is required. '
     'The application defaults outside Compose are 3s for registry requests and 60m for registry lookup caching. '
     'The MI container is configured for two CPUs and 2 GB; other stack services need additional resources.')
heading('Start three instances sharing the queue')
code('docker compose -f docker-compose.local.yml stop\n'
     'docker compose -f docker-compose.ha.yml up -d --wait')
text('Health endpoints are on 8088, 8089, and 8090. Each instance uses the same nonexclusive '
     'ingress queue. To return to one instance:')
code('docker compose -f docker-compose.ha.yml stop\n'
     './scripts/install.sh --existing')
text('PARTITION_COUNT > 0 selects a separately pre-provisioned partitioned queue. Do not use '
     'the partition provisioning helper to resize a live queue without a migration plan: recreation can remove queued data.')
heading('Stop the local installation')
code('./scripts/demo-teardown.sh')
text('This stops/removes the demo containers and preserves registry volumes and the shared network. '
     'Re-run ./scripts/install.sh to start again. Back up an existing registry before an upgrade; '
     'the installer does not perform automatic database migration or rollback.')

# 10: Concrete recovery checks and precise evidence boundaries.
page('Troubleshoot and verify your setup')
table(['Symptom', 'Check and recovery'], [
    ['Docker is unavailable', 'Start Docker Desktop and complete required organization sign-in; docker ps must succeed.'],
    ['Registry archive not found', 'Confirm its exact filename in Downloads or set SCHEMA_REGISTRY_DIST to the full archive path. Keep the .md5 beside it.'],
    ['Port is already allocated', 'Identify the existing service. Stop it or use existing services instead of starting a second local stack.'],
    ['MI cannot reach the registry', 'Check the /apis/registry/v3 suffix, Docker DNS, firewall/TLS, and readonly credentials. Container localhost is not the host.'],
    ['No mapping matched', 'Confirm the queue subscribes to the topic. Supply both schema_group and schema_name, or configure a matching app.mappings entry.'],
    ['New version is not selected', 'Confirm ENABLED state and latest/fixed strategy; account for metadata cache TTL or restart to refresh.'],
    ['GHCR pull returns 401', 'Use authorized GitHub Packages credentials with docker login ghcr.io, or use the public installation archive.'],
], [1.85, 5.15])
heading('Collect the relevant service logs')
code('docker logs --tail 100 schema-validation-micro-integration\n'
     'docker logs --tail 100 schema-registry-backend\n'
     'docker logs --tail 100 schema-registry-idp\n'
     'curl --fail http://localhost:8088/actuator/health')
text('VALIDATED means payload validation succeeded. REJECT means the payload failed a schema '
     'or size check. PROCESSING_ERROR means mapping, registry, validation execution, or publishing '
     'could not complete. Inspect mi_failure_category and mi_failure_reason. Schema property names '
     'are case-sensitive. An incomplete pair of schema properties falls back to topic mapping.')
heading('Run automated verification')
text('On a development host with Java 17+ and Maven 3.9+:')
code('./scripts/verify.sh\n'
     'MI_REGISTRY_TEST_URL=http://localhost:18081/apis/registry/v3 \\\n'
     'MI_BROKER_TEST_URL=tcp://localhost:55554 ./scripts/verify.sh')
text('The second command opts into real registry/broker tests. It requires the default demo topic '
     'subscriptions, the same registry/broker as the running MI, and developer registry credentials '
     '(MI_REGISTRY_TEST_USERNAME / MI_REGISTRY_TEST_PASSWORD). Tests create mi-compat groups in a '
     'disposable registry. CI checks Java 17, 21, and 25 and builds the image. SDKPerf load tests '
     'are separate; these checks do not establish production throughput, global ordering, exactly-once, or zero-loss delivery.')
heading('Reference links')
text(f'Integration release: {REPO}/releases/tag/v{VERSION}')
text('Detailed configuration: docs/CONFIGURATION.md in the installation directory.')
text('REST header reference: https://docs.solace.com/API/RESTMessagingPrtl/Solace-REST-Message-Encoding.htm')
text('Registry deployment and backup: https://docs.solace.com/Schema-Registry/deploying-schema-registry-docker.htm')

doc.core_properties.title = 'Schema Validation Micro Integration Installation and Setup Guide'
doc.core_properties.subject = f'Integration {VERSION} and Solace Schema Registry 1.1.2 GA'
doc.core_properties.author = 'Solace'
doc.save(OUTPUT)
print(OUTPUT)
