package com.solace.schemavalidator.registry;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.solace.schemavalidator.TestMeters;
import com.solace.schemavalidator.config.AppProperties;
import com.solace.schemavalidator.resolution.ArtifactType;
import com.solace.schemavalidator.validation.*;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;
import com.solacesystems.jcsmp.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import static org.assertj.core.api.Assertions.*;

/** Opt-in contract tests against an actual registry; creates a unique test group. */
@EnabledIfEnvironmentVariable(named = "MI_REGISTRY_TEST_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RegistryCompatibilityIT {
    private final String group = "mi-compat-" + UUID.randomUUID();
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final HttpClient http = HttpClient.newHttpClient();
    private String baseUrl;
    private String auth;
    private SchemaBundleLoader loader;
    private JCSMPSession broker;
    private XMLMessageProducer producer;
    private final BlockingQueue<Map<String, String>> results = new LinkedBlockingQueue<>();
    private String topic;

    @BeforeAll
    void connect() throws Exception {
        baseUrl = System.getenv("MI_REGISTRY_TEST_URL").replaceAll("/+$", "");
        String username = System.getenv().getOrDefault("MI_REGISTRY_TEST_USERNAME", "sr-developer");
        String password = System.getenv().getOrDefault("MI_REGISTRY_TEST_PASSWORD", "devPassword");
        auth = "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var properties = new AppProperties.SchemaRegistry();
        properties.setBaseUrl(baseUrl);
        properties.setUsername(username);
        properties.setPassword(password);
        var client = new HttpSchemaRegistryClient(http, mapper, properties);
        assertThat(client.getSystemInfo()).containsKey("version");
        loader = new SchemaBundleLoader(client, Caffeine.newBuilder().build(), TestMeters.metrics());
        post("/groups", Map.of("groupId", group));
        if (System.getenv("MI_BROKER_TEST_URL") != null) {
            var settings = new JCSMPProperties();
            settings.setProperty(JCSMPProperties.HOST, System.getenv("MI_BROKER_TEST_URL"));
            settings.setProperty(JCSMPProperties.VPN_NAME, System.getenv().getOrDefault("MI_BROKER_TEST_VPN", "default"));
            settings.setProperty(JCSMPProperties.USERNAME, System.getenv().getOrDefault("MI_BROKER_TEST_USERNAME", "default"));
            settings.setProperty(JCSMPProperties.PASSWORD, System.getenv().getOrDefault("MI_BROKER_TEST_PASSWORD", "default"));
            broker = JCSMPFactory.onlyInstance().createSession(settings);
            broker.connect();
            topic = "Acme/test/test/DATASCIENCE/RAW/FromEqt/" + group;
            var consumer = broker.getMessageConsumer(new XMLMessageListener() {
                public void onReceive(BytesXMLMessage message) {
                    try {
                        var properties = message.getProperties();
                        if (properties != null && properties.containsKey("mi_validation_status")) {
                            results.add(Map.of("status", properties.getString("mi_validation_status"),
                                "trace", properties.getString("mi_trace_id"), "type", properties.getString("mi_schema_type")));
                        }
                    } catch (SDTException error) { throw new IllegalStateException(error); }
                }
                public void onException(JCSMPException error) { throw new IllegalStateException(error); }
            });
            broker.addSubscription(JCSMPFactory.onlyInstance().createTopic(topic));
            consumer.start();
            producer = broker.getMessageProducer(new JCSMPStreamingPublishEventHandler() {
                public void responseReceived(String id) { }
                public void handleError(String id, JCSMPException error, long timestamp) {
                    throw new IllegalStateException(error);
                }
            });
        }
    }

    @AfterAll
    void closeBroker() { if (broker != null) broker.closeSession(); }

    private void roundTrip(String artifact, String type, byte[] payload, String status) throws Exception {
        if (broker == null) return;
        String trace = UUID.randomUUID().toString();
        BytesMessage message = JCSMPFactory.onlyInstance().createMessage(BytesMessage.class);
        message.writeBytes(payload);
        message.setDeliveryMode(DeliveryMode.PERSISTENT);
        var headers = JCSMPFactory.onlyInstance().createMap();
        headers.putString("schema_group", group);
        headers.putString("schema_name", artifact);
        headers.putString("schema_version", "1");
        headers.putString("mi_trace_id", trace);
        message.setProperties(headers);
        producer.send(message, JCSMPFactory.onlyInstance().createTopic(topic));
        assertThat(results.poll(20, TimeUnit.SECONDS)).as("broker round trip for %s", artifact)
            .containsEntry("trace", trace).containsEntry("status", status).containsEntry("type", type);
    }

    private void post(String path, Map<String, ?> body) throws Exception {
        var response = http.send(HttpRequest.newBuilder(URI.create(baseUrl + path))
            .header("Authorization", auth).header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body))).build(),
            HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).withFailMessage("Registry HTTP %s: %s", response.statusCode(), response.body())
            .isBetween(200, 299);
    }

    private void register(String id, String type, String content, Map<String, String>... references) throws Exception {
        post("/groups/" + group + "/artifacts", Map.of("artifactId", id, "artifactType", type,
            "firstVersion", Map.of("version", "1", "content", Map.of("content", content,
                "contentType", "XML".equals(type) ? "application/xml" : "application/json", "references", List.of(references)))));
    }

    @Test
    void jsonSchemaWithReferencesAndLatestEnabledResolution() throws Exception {
        register("metadata", "JSON", Files.readString(Path.of("schemas/json/mqtt-envelope/metadata.v1.schema.json")));
        register("context", "JSON", Files.readString(Path.of("schemas/json/mqtt-envelope/context.generic.v1.schema.json")));
        register("files", "JSON", Files.readString(Path.of("schemas/json/mqtt-envelope/pub-files.v1.schema.json")),
            Map.of("groupId", group, "artifactId", "metadata", "version", "1", "name", "https://example.com/schemas/mqtt-envelope/metadata.v1.schema.json"),
            Map.of("groupId", group, "artifactId", "context", "version", "1", "name", "https://example.com/schemas/mqtt-envelope/context.generic.v1.schema.json"));
        register("table", "JSON", Files.readString(Path.of("schemas/json/mqtt-envelope/pub-table.v1.schema.json")),
            Map.of("groupId", group, "artifactId", "metadata", "version", "1", "name", "https://example.com/schemas/mqtt-envelope/metadata.v1.schema.json"),
            Map.of("groupId", group, "artifactId", "context", "version", "1", "name", "https://example.com/schemas/mqtt-envelope/context.generic.v1.schema.json"));
        var tableValidator = new JsonSchemaValidatorFactory(mapper).compile(loader.load(group, "table", "1"));
        assertThat(tableValidator.validate(Files.readAllBytes(Path.of("samples/json/acme-shopfloor-fromsi.valid.json"))).valid()).isTrue();
        assertThat(tableValidator.validate(Files.readAllBytes(Path.of("samples/json/acme-pub-table.valid.json"))).valid()).isTrue();
        assertThat(tableValidator.validate(Files.readAllBytes(Path.of("samples/json/acme-pub-table.invalid.json"))).valid()).isFalse();
        assertThat(tableValidator.validate(Files.readAllBytes(Path.of("samples/json/acme-pub-table.not-json.bin"))).valid()).isFalse();
        var bundle = loader.load(group, "files", "latest-enabled");
        assertThat(bundle.documents()).hasSize(3);
        var validator = new JsonSchemaValidatorFactory(mapper).compile(bundle);
        for (String valid : List.of("acme-pub-files.valid.json")) {
            assertThat(validator.validate(Files.readAllBytes(Path.of("samples/json", valid))).valid()).as(valid).isTrue();
        }
        for (String invalid : List.of("acme-pub-files.invalid.json", "acme-pub-files.bad-checksum.json", "acme-pub-files.bad-doctype.json")) {
            assertThat(validator.validate(Files.readAllBytes(Path.of("samples/json", invalid))).valid()).as(invalid).isFalse();
        }
        roundTrip("files", "JSON", Files.readAllBytes(Path.of("samples/json/acme-pub-files.valid.json")), "VALIDATED");
        roundTrip("files", "JSON", Files.readAllBytes(Path.of("samples/json/acme-pub-files.invalid.json")), "REJECT");
    }

    @Test
    void xmlSchemaWithRegistryInclude() throws Exception {
        register("xml-types", "XML", """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:simpleType name="Positive"><xs:restriction base="xs:integer"><xs:minInclusive value="1"/></xs:restriction></xs:simpleType>
            </xs:schema>
            """);
        register("xml-root", "XML", """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:include schemaLocation="types.xsd"/><xs:element name="reading" type="Positive"/>
            </xs:schema>
            """, Map.of("groupId", group, "artifactId", "xml-types", "version", "1", "name", "types.xsd"));
        var bundle = loader.load(group, "xml-root", "1");
        assertThat(bundle.root().artifactType()).isEqualTo(ArtifactType.XSD);
        var validator = new XmlSchemaValidatorFactory().compile(bundle);
        assertThat(validator.validate("<reading>7</reading>".getBytes()).valid()).isTrue();
        assertThat(validator.validate("<reading>bad</reading>".getBytes()).valid()).isFalse();
        roundTrip("xml-root", "XML", "<reading>7</reading>".getBytes(), "VALIDATED");
        roundTrip("xml-root", "XML", "<reading>bad</reading>".getBytes(), "REJECT");
    }

    @Test
    void rawBinaryAvro() throws Exception {
        register("avro-root", "AVRO", "{\"type\":\"record\",\"name\":\"Reading\",\"fields\":[{\"name\":\"value\",\"type\":\"int\"}]}");
        var validator = new AvroSchemaValidatorFactory().compile(loader.load(group, "avro-root", "1"));
        assertThat(validator.validate(new byte[]{14}).valid()).isTrue();
        assertThat(validator.validate(new byte[]{(byte) 128}).valid()).isFalse();
        roundTrip("avro-root", "AVRO", new byte[]{14}, "VALIDATED");
        roundTrip("avro-root", "AVRO", new byte[]{(byte) 128}, "REJECT");
    }

    @Test
    void rawBinaryProtobufWithRegistryImport() throws Exception {
        register("proto-child", "PROTOBUF", """
            syntax = "proto3";
            package test;
            message Child { string name = 1; }
            """);
        register("proto-root", "PROTOBUF", """
            syntax = "proto3";
            package test;
            import "shared/child.proto";
            message Root { Child child = 1; }
            """, Map.of("groupId", group, "artifactId", "proto-child", "version", "1", "name", "shared/child.proto"));
        var validator = new ProtobufSchemaValidatorFactory().compile(loader.load(group, "proto-root", "latest-enabled"));
        assertThat(validator.validate(new byte[]{10, 3, 10, 1, 65}).valid()).isTrue();
        assertThat(validator.validate(new byte[]{10, 5, 10}).valid()).isFalse();
        roundTrip("proto-root", "PROTOBUF", new byte[]{10, 3, 10, 1, 65}, "VALIDATED");
        roundTrip("proto-root", "PROTOBUF", new byte[]{10, 5, 10}, "REJECT");
    }
}
