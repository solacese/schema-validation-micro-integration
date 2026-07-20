#!/usr/bin/env python3
"""
Generate the Schema Validation Micro-Integration Installation & Operations Guide
as a professionally formatted Word document (.docx).
"""

from docx import Document
from docx.shared import Inches, Pt, Cm, RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.table import WD_TABLE_ALIGNMENT
from docx.enum.section import WD_ORIENT
from docx.oxml.ns import qn, nsdecls
from docx.oxml import parse_xml
import os

# Output path
OUTPUT_DIR = os.path.dirname(os.path.abspath(__file__))
OUTPUT_PATH = os.path.join(OUTPUT_DIR, "Schema-Validation-MI-Installation-Guide.docx")

doc = Document()

# ─────────────────────────────────────────────────────────────────────────────
# Helper functions
# ─────────────────────────────────────────────────────────────────────────────

def set_cell_shading(cell, color):
    """Set background shading on a table cell."""
    shading_elm = parse_xml(f'<w:shd {nsdecls("w")} w:fill="{color}"/>')
    cell._tc.get_or_add_tcPr().append(shading_elm)


def set_table_borders(table):
    """Apply borders to all cells in a table."""
    tbl = table._tbl
    tblPr = tbl.tblPr if tbl.tblPr is not None else parse_xml(f'<w:tblPr {nsdecls("w")}/>')
    borders = parse_xml(
        f'<w:tblBorders {nsdecls("w")}>'
        '<w:top w:val="single" w:sz="4" w:space="0" w:color="999999"/>'
        '<w:left w:val="single" w:sz="4" w:space="0" w:color="999999"/>'
        '<w:bottom w:val="single" w:sz="4" w:space="0" w:color="999999"/>'
        '<w:right w:val="single" w:sz="4" w:space="0" w:color="999999"/>'
        '<w:insideH w:val="single" w:sz="4" w:space="0" w:color="999999"/>'
        '<w:insideV w:val="single" w:sz="4" w:space="0" w:color="999999"/>'
        '</w:tblBorders>'
    )
    tblPr.append(borders)
    if tbl.tblPr is None:
        tbl.append(tblPr)


def add_code_block(doc, code_text):
    """Add a code block with monospaced font and light gray background."""
    for line in code_text.strip().split('\n'):
        p = doc.add_paragraph()
        p.paragraph_format.space_before = Pt(0)
        p.paragraph_format.space_after = Pt(0)
        p.paragraph_format.left_indent = Cm(1)
        run = p.add_run(line)
        run.font.name = 'Courier New'
        run.font.size = Pt(9)
        run.font.color.rgb = RGBColor(0x1A, 0x1A, 0x1A)
        # Set shading on the paragraph
        pPr = p._p.get_or_add_pPr()
        shading = parse_xml(f'<w:shd {nsdecls("w")} w:val="clear" w:color="auto" w:fill="F2F2F2"/>')
        pPr.append(shading)


def add_table(doc, headers, rows, col_widths=None):
    """Add a formatted table with header row shading."""
    table = doc.add_table(rows=1 + len(rows), cols=len(headers))
    table.alignment = WD_TABLE_ALIGNMENT.LEFT
    set_table_borders(table)

    # Header row
    for i, header in enumerate(headers):
        cell = table.rows[0].cells[i]
        cell.text = ''
        p = cell.paragraphs[0]
        run = p.add_run(header)
        run.bold = True
        run.font.size = Pt(10)
        set_cell_shading(cell, "2E4057")
        run.font.color.rgb = RGBColor(0xFF, 0xFF, 0xFF)

    # Data rows
    for row_idx, row_data in enumerate(rows):
        for col_idx, cell_text in enumerate(row_data):
            cell = table.rows[row_idx + 1].cells[col_idx]
            cell.text = ''
            p = cell.paragraphs[0]
            run = p.add_run(str(cell_text))
            run.font.size = Pt(9)
            # Alternate row shading
            if row_idx % 2 == 0:
                set_cell_shading(cell, "F7F9FC")

    # Set column widths if provided
    if col_widths:
        for i, width in enumerate(col_widths):
            for row in table.rows:
                row.cells[i].width = width

    doc.add_paragraph()  # spacing after table
    return table


def add_bullet(doc, text, level=0):
    """Add a bullet point."""
    p = doc.add_paragraph(text, style='List Bullet')
    if level > 0:
        p.paragraph_format.left_indent = Cm(1.27 * (level + 1))
    return p


def add_bullet_code(doc, text):
    """Add a bullet point with code-style text."""
    p = doc.add_paragraph(style='List Bullet')
    run = p.add_run(text)
    run.font.name = 'Courier New'
    run.font.size = Pt(9)
    return p


# ─────────────────────────────────────────────────────────────────────────────
# TITLE PAGE
# ─────────────────────────────────────────────────────────────────────────────

# Add some vertical spacing
for _ in range(6):
    doc.add_paragraph()

title = doc.add_paragraph()
title.alignment = WD_ALIGN_PARAGRAPH.CENTER
run = title.add_run("Schema Validation\nMicro-Integration")
run.font.size = Pt(36)
run.font.color.rgb = RGBColor(0x2E, 0x40, 0x57)
run.bold = True

doc.add_paragraph()

subtitle = doc.add_paragraph()
subtitle.alignment = WD_ALIGN_PARAGRAPH.CENTER
run = subtitle.add_run("Installation & Operations Guide")
run.font.size = Pt(20)
run.font.color.rgb = RGBColor(0x5A, 0x6A, 0x7A)

doc.add_paragraph()
doc.add_paragraph()

version_info = doc.add_paragraph()
version_info.alignment = WD_ALIGN_PARAGRAPH.CENTER
run = version_info.add_run("Version 1.0")
run.font.size = Pt(12)
run.font.color.rgb = RGBColor(0x88, 0x88, 0x88)

doc.add_page_break()

# ─────────────────────────────────────────────────────────────────────────────
# TABLE OF CONTENTS
# ─────────────────────────────────────────────────────────────────────────────

doc.add_heading("Table of Contents", level=1)

toc_entries = [
    "1. Overview",
    "2. Prerequisites",
    "3. Installation — Single Instance",
    "    3.1 Clone and configure",
    "    3.2 Build the Docker image",
    "    3.3 Start the MI",
    "    3.4 Verify health",
    "4. Configuration Reference",
    "    4.1 Environment variables",
    "    4.2 Cache tuning",
    "    4.3 Topic mappings (application.yml)",
    "5. High Availability Setup",
    "    5.1 Architecture",
    "    5.2 Deploy 3-instance cluster",
    "    5.3 Verify HA cluster",
    "    5.4 Failover behavior",
    "    5.5 Rolling restart procedure",
    "    5.6 Scaling considerations",
    "6. Partitioned Queue Setup",
    "7. Schema Registry Setup",
    "    7.1 Register schemas",
    "    7.2 Custom schemas",
    "8. Monitoring & Health Checks",
    "    8.1 Endpoints",
    "    8.2 Key metrics",
    "    8.3 Docker healthcheck",
    "9. Performance Tuning",
    "    9.1 JVM settings",
    "    9.2 Consumer concurrency",
    "    9.3 Cache sizing",
    "    9.4 Registry timeout",
    "10. Security",
    "    10.1 Container hardening",
    "    10.2 Network",
    "11. Solace Cloud Deployment",
    "12. Troubleshooting",
]

for entry in toc_entries:
    p = doc.add_paragraph()
    p.paragraph_format.space_before = Pt(2)
    p.paragraph_format.space_after = Pt(2)
    if entry.startswith("    "):
        p.paragraph_format.left_indent = Cm(1.5)
        run = p.add_run(entry.strip())
        run.font.size = Pt(10)
        run.font.color.rgb = RGBColor(0x55, 0x55, 0x55)
    else:
        run = p.add_run(entry)
        run.bold = True
        run.font.size = Pt(11)
        run.font.color.rgb = RGBColor(0x2E, 0x40, 0x57)

doc.add_page_break()

# ─────────────────────────────────────────────────────────────────────────────
# 1. OVERVIEW
# ─────────────────────────────────────────────────────────────────────────────

doc.add_heading("1. Overview", level=1)

doc.add_paragraph(
    "The Schema Validation Micro-Integration (MI) validates messages flowing through "
    "Solace PubSub+ against JSON schemas stored in the Solace Schema Registry. It operates "
    "as a message processor: consuming messages from a durable queue, validating against the "
    "resolved schema, and republishing on the same topic with validation metadata as user properties."
)

doc.add_paragraph("Key capabilities:")
add_bullet(doc, "Multi-format validation: JSON Schema, Avro, Protobuf, XSD")
add_bullet(doc, "Topic-based and header-based schema resolution")
add_bullet(doc, "Dual-broker support (read from one VPN, write to another)")
add_bullet(doc, "High Availability with non-exclusive queue binding")
add_bullet(doc, "Partitioned queue support for ordered processing")
add_bullet(doc, "Four-level caching (topic resolution, registry metadata, schema bundles, compiled validators)")
add_bullet(doc, "Hardened container with read-only filesystem, non-root execution, resource limits")

doc.add_page_break()

# ─────────────────────────────────────────────────────────────────────────────
# 2. PREREQUISITES
# ─────────────────────────────────────────────────────────────────────────────

doc.add_heading("2. Prerequisites", level=1)

add_table(doc,
    headers=["Component", "Minimum Version", "Notes"],
    rows=[
        ["Docker", "24+", "Docker Compose V2 required"],
        ["Java", "17+", "For building from source"],
        ["Maven", "3.9+", "For building from source"],
        ["Solace PubSub+", "10.x+", "Standard or Enterprise Edition"],
        ["Solace Schema Registry", "3.0+", "With REST API v3"],
        ["Python", "3.8+", "For helper scripts"],
    ],
    col_widths=[Inches(2), Inches(1.5), Inches(3)]
)

doc.add_page_break()

# ─────────────────────────────────────────────────────────────────────────────
# 3. INSTALLATION — SINGLE INSTANCE
# ─────────────────────────────────────────────────────────────────────────────

doc.add_heading("3. Installation — Single Instance", level=1)

doc.add_heading("3.1 Clone and configure", level=2)

add_code_block(doc, """\
git clone https://github.com/solacese/schema-validation-micro-integration.git
cd schema-validation-micro-integration
cp .env.example .env""")

doc.add_paragraph()
p = doc.add_paragraph("Edit ")
run = p.add_run(".env")
run.font.name = 'Courier New'
run.font.size = Pt(9)
p.add_run(" with your broker and registry details:")

add_code_block(doc, """\
SOLACE_INPUT_BROKER=tcp://your-broker:55555
SOLACE_INPUT_VPN=your-vpn
SOLACE_INPUT_USERNAME=your-user
SOLACE_INPUT_PASSWORD=your-password
SOLACE_OUTPUT_BROKER=tcp://your-broker:55555
SOLACE_OUTPUT_VPN=your-vpn
SOLACE_OUTPUT_USERNAME=your-user
SOLACE_OUTPUT_PASSWORD=your-password
SCHEMA_REGISTRY_BASE_URL=http://your-registry:8080/apis/registry/v3
SCHEMA_REGISTRY_USERNAME=sr-readonly
SCHEMA_REGISTRY_PASSWORD=your-password""")

doc.add_heading("3.2 Build the Docker image", level=2)

add_code_block(doc, "docker compose -f docker-compose.local.yml build")

doc.add_paragraph()
doc.add_paragraph("For Docker Hardened Image (DHI):")

add_code_block(doc, 'docker build --build-arg RUNTIME_IMAGE=dhi.io/eclipse-temurin:17 -t schema-validation-micro-integration:dhi .')

doc.add_heading("3.3 Start the MI", level=2)

add_code_block(doc, "docker compose -f docker-compose.local.yml up -d")

doc.add_heading("3.4 Verify health", level=2)

add_code_block(doc, "curl http://localhost:8088/actuator/health")

p = doc.add_paragraph("Expected: ")
run = p.add_run('{"status":"UP"}')
run.font.name = 'Courier New'
run.font.size = Pt(9)

doc.add_page_break()

# ─────────────────────────────────────────────────────────────────────────────
# 4. CONFIGURATION REFERENCE
# ─────────────────────────────────────────────────────────────────────────────

doc.add_heading("4. Configuration Reference", level=1)

doc.add_heading("4.1 Environment variables", level=2)

add_table(doc,
    headers=["Variable", "Default", "Description"],
    rows=[
        ["SOLACE_INPUT_BROKER", "tcp://solace-broker:55555", "Source broker (SMF, WSS, or TCPS)"],
        ["SOLACE_INPUT_VPN", "default", "Input message VPN"],
        ["SOLACE_INPUT_USERNAME", "default", "Input client username"],
        ["SOLACE_INPUT_PASSWORD", "default", "Input client password"],
        ["SOLACE_OUTPUT_BROKER", "(same as input)", "Target broker for validated output"],
        ["SOLACE_OUTPUT_VPN", "(same as input)", "Output message VPN"],
        ["SOLACE_OUTPUT_USERNAME", "(same as input)", "Output client username"],
        ["SOLACE_OUTPUT_PASSWORD", "(same as input)", "Output client password"],
        ["SCHEMA_REGISTRY_BASE_URL", "http://localhost:18081/apis/registry/v3", "Schema Registry REST endpoint"],
        ["SCHEMA_REGISTRY_USERNAME", "sr-readonly", "Registry basic auth username"],
        ["SCHEMA_REGISTRY_PASSWORD", "roPassword", "Registry basic auth password"],
        ["SCHEMA_REGISTRY_REQUEST_TIMEOUT", "3s", "HTTP timeout for registry calls"],
        ["CONSUMER_CONCURRENCY", "8", "Parallel consumer threads"],
        ["MAX_PAYLOAD_BYTES", "1048576", "Maximum message payload (bytes)"],
        ["VALIDATION_TIMEOUT", "5s", "Per-message validation timeout"],
        ["PARTITION_COUNT", "0", "Partitioned queue (0 = disabled)"],
        ["PARTITION_REBALANCE_DELAY", "5", "Seconds before partition rebalance"],
        ["SCHEMA_PREWARM_ENABLED", "true", "Prewarm schema cache on startup"],
        ["PORT", "8088", "HTTP management port"],
    ],
    col_widths=[Inches(2.8), Inches(2.2), Inches(2.5)]
)

doc.add_heading("4.2 Cache tuning", level=2)

add_table(doc,
    headers=["Variable", "Default", "Description"],
    rows=[
        ["TOPIC_RESOLUTION_TTL", "15m", "How long topic→schema mappings are cached"],
        ["REGISTRY_LOOKUP_TTL", "60m", "How long registry metadata is cached"],
        ["SCHEMA_BUNDLE_TTL", "60m", "How long resolved schema bundles are cached"],
        ["COMPILED_VALIDATOR_TTL", "60m", "How long compiled validators are cached"],
        ["MAX_TOPIC_CACHE_ENTRIES", "5000", "Max topic resolution cache entries"],
        ["MAX_REGISTRY_CACHE_ENTRIES", "1000", "Max registry metadata cache entries"],
        ["MAX_SCHEMA_CACHE_ENTRIES", "1000", "Max schema bundle cache entries"],
        ["MAX_VALIDATOR_CACHE_ENTRIES", "1000", "Max compiled validator cache entries"],
    ],
    col_widths=[Inches(2.8), Inches(1.2), Inches(3.5)]
)

doc.add_heading("4.3 Topic mappings (application.yml)", level=2)

p = doc.add_paragraph("Topic mappings are configured in ")
run = p.add_run("src/main/resources/application.yml")
run.font.name = 'Courier New'
run.font.size = Pt(9)
p.add_run(" under ")
run = p.add_run("app.mappings")
run.font.name = 'Courier New'
run.font.size = Pt(9)
p.add_run(". Each mapping defines:")

add_bullet(doc, "id: Unique identifier for the mapping")
add_bullet(doc, "topic-pattern: Solace topic pattern with {capture} groups")
add_bullet(doc, "schema-group: Schema Registry group name")
add_bullet(doc, "artifact-id: Schema artifact within the group")
add_bullet(doc, "artifact-type: JSON, AVRO, PROTOBUF, or XSD")
add_bullet(doc, "version-strategy: How to resolve the schema version (LATEST_ENABLED, TOPIC_CAPTURE, BRANCH, FIXED)")

doc.add_page_break()

# ─────────────────────────────────────────────────────────────────────────────
# 5. HIGH AVAILABILITY SETUP
# ─────────────────────────────────────────────────────────────────────────────

doc.add_heading("5. High Availability Setup", level=1)

doc.add_heading("5.1 Architecture", level=2)

doc.add_paragraph(
    "The MI achieves high availability through Solace's non-exclusive queue binding:"
)
add_bullet(doc, "Multiple MI instances bind to the same durable queue")
add_bullet(doc, "The broker distributes messages across active consumers")
add_bullet(doc, "If one instance fails, messages are automatically redelivered to surviving instances")
add_bullet(doc, "Zero message loss during instance failures or rolling restarts")

doc.add_paragraph()
add_code_block(doc, """\
Publishers → Topic Subscriptions → Durable Queue (non-exclusive)
                                      ├── MI Instance 1 (8 threads)
                                      ├── MI Instance 2 (8 threads)
                                      └── MI Instance 3 (8 threads)""")

doc.add_heading("5.2 Deploy 3-instance cluster", level=2)

add_code_block(doc, "docker compose -f docker-compose.ha.yml up -d")

doc.add_paragraph()
doc.add_paragraph("This creates three MI instances:")

add_table(doc,
    headers=["Instance", "Port", "Container"],
    rows=[
        ["MI-1", "8088", "mi-1"],
        ["MI-2", "8089", "mi-2"],
        ["MI-3", "8090", "mi-3"],
    ],
    col_widths=[Inches(2), Inches(1.5), Inches(2)]
)

doc.add_paragraph("Override host ports if needed:")
add_code_block(doc, "MI_1_HOST_PORT=9088 MI_2_HOST_PORT=9089 MI_3_HOST_PORT=9090 docker compose -f docker-compose.ha.yml up -d")

doc.add_heading("5.3 Verify HA cluster", level=2)

doc.add_paragraph("Check all instances are healthy:")
add_code_block(doc, """\
curl http://localhost:8088/actuator/health
curl http://localhost:8089/actuator/health
curl http://localhost:8090/actuator/health""")

doc.add_heading("5.4 Failover behavior", level=2)

doc.add_paragraph("When an instance stops:")
p = doc.add_paragraph("1. The broker detects the disconnection (within seconds)")
p.paragraph_format.left_indent = Cm(0.5)
p = doc.add_paragraph("2. Messages assigned to that consumer are redelivered to remaining instances")
p.paragraph_format.left_indent = Cm(0.5)
p = doc.add_paragraph("3. No messages are lost — the queue holds unacknowledged messages until redelivery")
p.paragraph_format.left_indent = Cm(0.5)
p = doc.add_paragraph("4. When the instance recovers, it reconnects and begins receiving new messages")
p.paragraph_format.left_indent = Cm(0.5)

doc.add_heading("5.5 Rolling restart procedure", level=2)

steps = [
    "Stop one instance: docker stop mi-1",
    "Wait for health check to confirm redistribution (10-15 seconds)",
    "Start the instance: docker start mi-1",
    "Wait for healthy status: curl http://localhost:8088/actuator/health",
    "Repeat for next instance",
]
for i, step in enumerate(steps, 1):
    p = doc.add_paragraph(f"{i}. {step}")
    p.paragraph_format.left_indent = Cm(0.5)

doc.add_heading("5.6 Scaling considerations", level=2)

add_table(doc,
    headers=["Instances", "Consumer Threads", "Aggregate Throughput"],
    rows=[
        ["1", "8", "~1,000-2,000 msg/s"],
        ["3", "24", "~3,000-5,000 msg/s"],
        ["5+", "40+", "Limited by broker/registry"],
    ],
    col_widths=[Inches(1.5), Inches(2), Inches(3)]
)

doc.add_page_break()

# ─────────────────────────────────────────────────────────────────────────────
# 6. PARTITIONED QUEUE SETUP
# ─────────────────────────────────────────────────────────────────────────────

doc.add_heading("6. Partitioned Queue Setup", level=1)

doc.add_paragraph("For ordered processing within a partition key:")

add_code_block(doc, "./scripts/provision-partitioned-queue.sh 3")

doc.add_paragraph()
doc.add_paragraph(
    "This creates the queue with 3 partitions and adds all required topic subscriptions."
)

p = doc.add_paragraph("Set the environment variable: ")
run = p.add_run("PARTITION_COUNT=3")
run.font.name = 'Courier New'
run.font.size = Pt(9)

doc.add_paragraph()
doc.add_paragraph(
    "Messages with the same partition key are always processed by the same consumer, "
    "ensuring ordering within a partition."
)

doc.add_page_break()

# ─────────────────────────────────────────────────────────────────────────────
# 7. SCHEMA REGISTRY SETUP
# ─────────────────────────────────────────────────────────────────────────────

doc.add_heading("7. Schema Registry Setup", level=1)

doc.add_heading("7.1 Register schemas", level=2)

add_code_block(doc, "./scripts/register-schemas.sh")

doc.add_paragraph()
doc.add_paragraph(
    "This registers the demo schemas (metadata, context-generic, pub-files, pub-table) "
    "into the manufacturing-iot group."
)

doc.add_heading("7.2 Custom schemas", level=2)

doc.add_paragraph("To add your own schemas:")
p = doc.add_paragraph("1. Create the JSON Schema file in schemas/json/your-group/")
p.paragraph_format.left_indent = Cm(0.5)
p = doc.add_paragraph("2. Register using the Registry REST API or the registration script as a template")
p.paragraph_format.left_indent = Cm(0.5)
p = doc.add_paragraph("3. Add a topic mapping in application.yml")
p.paragraph_format.left_indent = Cm(0.5)
p = doc.add_paragraph("4. Restart the MI (or wait for cache expiry)")
p.paragraph_format.left_indent = Cm(0.5)

doc.add_page_break()

# ─────────────────────────────────────────────────────────────────────────────
# 8. MONITORING & HEALTH CHECKS
# ─────────────────────────────────────────────────────────────────────────────

doc.add_heading("8. Monitoring & Health Checks", level=1)

doc.add_heading("8.1 Endpoints", level=2)

add_table(doc,
    headers=["Endpoint", "Purpose"],
    rows=[
        ["/actuator/health", "Liveness + readiness (includes registry connectivity)"],
        ["/actuator/metrics", "Micrometer metrics (validation counts, latencies)"],
        ["/actuator/info", "Build info"],
        ["/actuator/bindings", "Spring Cloud Stream binding status"],
    ],
    col_widths=[Inches(2.5), Inches(4.5)]
)

doc.add_heading("8.2 Key metrics", level=2)

add_table(doc,
    headers=["Metric", "Description"],
    rows=[
        ["schema.validation.total", "Total messages processed"],
        ["schema.validation.validated", "Successfully validated messages"],
        ["schema.validation.rejected", "Messages that failed validation"],
        ["schema.validation.errors", "Processing errors (registry unreachable, timeout)"],
        ["schema.validation.duration", "Validation latency histogram"],
        ["schema.cache.hits / misses", "Cache effectiveness"],
    ],
    col_widths=[Inches(2.8), Inches(4.2)]
)

doc.add_heading("8.3 Docker healthcheck", level=2)

doc.add_paragraph("The container includes a Java-based healthcheck (no shell required):")
add_bullet(doc, "Interval: 10s")
add_bullet(doc, "Timeout: 5s")
add_bullet(doc, "Start period: 45s (allows for JVM warmup)")
add_bullet(doc, "Retries: 5")

doc.add_page_break()

# ─────────────────────────────────────────────────────────────────────────────
# 9. PERFORMANCE TUNING
# ─────────────────────────────────────────────────────────────────────────────

doc.add_heading("9. Performance Tuning", level=1)

doc.add_heading("9.1 JVM settings", level=2)

doc.add_paragraph("Default:")
add_code_block(doc, "-XX:InitialRAMPercentage=25 -XX:MaxRAMPercentage=75 -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:+ExitOnOutOfMemoryError")

doc.add_paragraph()
doc.add_paragraph("For high-throughput scenarios, increase container memory and let the JVM scale:")
add_code_block(doc, "mem_limit: 4g")

doc.add_heading("9.2 Consumer concurrency", level=2)

p = doc.add_paragraph("The ")
run = p.add_run("CONSUMER_CONCURRENCY")
run.font.name = 'Courier New'
run.font.size = Pt(9)
p.add_run(" setting controls parallel processing threads:")

add_bullet(doc, "Default: 8")
add_bullet(doc, "For high throughput: 16-32")
add_bullet(doc, "Bound by: registry latency, CPU cores, broker connection limits")

doc.add_heading("9.3 Cache sizing", level=2)

doc.add_paragraph("For large topic spaces (>1000 distinct topics):")
add_code_block(doc, """\
MAX_TOPIC_CACHE_ENTRIES=10000
MAX_REGISTRY_CACHE_ENTRIES=5000
MAX_SCHEMA_CACHE_ENTRIES=5000
MAX_VALIDATOR_CACHE_ENTRIES=5000""")

doc.add_heading("9.4 Registry timeout", level=2)

doc.add_paragraph("If the registry is remote or high-latency:")
add_code_block(doc, """\
SCHEMA_REGISTRY_REQUEST_TIMEOUT=5s
REGISTRY_LOOKUP_TTL=120m""")

doc.add_page_break()

# ─────────────────────────────────────────────────────────────────────────────
# 10. SECURITY
# ─────────────────────────────────────────────────────────────────────────────

doc.add_heading("10. Security", level=1)

doc.add_heading("10.1 Container hardening", level=2)

doc.add_paragraph("The compose files enforce:")
add_bullet(doc, "Read-only root filesystem (writable: /tmp tmpfs only)")
add_bullet(doc, "Non-root user (UID 65532)")
add_bullet(doc, "All capabilities dropped")
add_bullet(doc, "no-new-privileges")
add_bullet(doc, "PID limit: 512")
add_bullet(doc, "CPU limit: 2.0 cores")
add_bullet(doc, "Memory limit: 2 GB")
add_bullet(doc, "Automatic restart (unless-stopped)")

doc.add_heading("10.2 Network", level=2)

add_bullet(doc, "Use TLS for broker connections (tcps:// or wss://)")
add_bullet(doc, "Use HTTPS for Schema Registry")
add_bullet(doc, "Keep registry credentials in secrets management (Docker secrets or Kubernetes secrets)")

doc.add_page_break()

# ─────────────────────────────────────────────────────────────────────────────
# 11. SOLACE CLOUD DEPLOYMENT
# ─────────────────────────────────────────────────────────────────────────────

doc.add_heading("11. Solace Cloud Deployment", level=1)

doc.add_paragraph("For Solace Cloud:")

add_code_block(doc, """\
SOLACE_INPUT_BROKER=wss://your-service.messaging.solace.cloud:443
SOLACE_INPUT_VPN=your-vpn
SOLACE_INPUT_USERNAME=solace-cloud-client
SOLACE_INPUT_PASSWORD=your-password""")

doc.add_paragraph()
doc.add_paragraph(
    "The MI automatically handles WSS→SMF translation for SDKPerf tools."
)

doc.add_page_break()

# ─────────────────────────────────────────────────────────────────────────────
# 12. TROUBLESHOOTING
# ─────────────────────────────────────────────────────────────────────────────

doc.add_heading("12. Troubleshooting", level=1)

add_table(doc,
    headers=["Symptom", "Cause", "Solution"],
    rows=[
        ["MI won't start (connection refused)", "Broker unreachable", "Verify SOLACE_INPUT_BROKER URL and port"],
        ["Health check returns DOWN", "Registry unreachable", "Verify SCHEMA_REGISTRY_BASE_URL"],
        ["Messages not being processed", "Queue not bound", "Check /actuator/bindings for active bindings"],
        ["PROCESSING_ERROR on all messages", "Schema not registered", "Run register-schemas.sh or check group/artifact names"],
        ["High latency", "Cache cold / registry slow", "Increase cache TTL, check registry response times"],
        ["Memory issues", "Cache too large", "Reduce MAX_*_CACHE_ENTRIES or increase mem_limit"],
    ],
    col_widths=[Inches(2.3), Inches(2.0), Inches(3.2)]
)

# ─────────────────────────────────────────────────────────────────────────────
# Save document
# ─────────────────────────────────────────────────────────────────────────────

doc.save(OUTPUT_PATH)
print(f"✅ Document generated: {OUTPUT_PATH}")
