package com.solace.schemavalidator.messaging;

import com.github.benmanes.caffeine.cache.Caffeine;
import tools.jackson.databind.ObjectMapper;
import com.solace.schemavalidator.TestMeters;
import com.solace.schemavalidator.config.AppProperties;
import com.solace.schemavalidator.observability.TraceIdExtractor;
import com.solace.schemavalidator.registry.SchemaBundle;
import com.solace.schemavalidator.registry.SchemaBundleLoader;
import com.solace.schemavalidator.registry.SchemaRegistryClient;
import com.solace.schemavalidator.registry.SchemaVersionCoordinate;
import com.solace.schemavalidator.registry.SchemaVersionMetadata;
import com.solace.schemavalidator.resolution.ArtifactType;
import com.solace.schemavalidator.resolution.TopicMappingResolver;
import com.solace.schemavalidator.resolution.TopicTemplateRenderer;
import com.solace.schemavalidator.resolution.VersionExpressionResolver;
import com.solace.schemavalidator.validation.AvroSchemaValidatorFactory;
import com.solace.schemavalidator.validation.CompiledValidator;
import com.solace.schemavalidator.validation.JsonSchemaValidatorFactory;
import com.solace.schemavalidator.validation.ValidatorService;
import com.solace.schemavalidator.validation.XmlSchemaValidatorFactory;
import com.solace.spring.cloud.stream.binder.messaging.SolaceBinderHeaders;
import com.solace.spring.cloud.stream.binder.messaging.SolaceHeaders;
import java.io.ByteArrayOutputStream;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.io.EncoderFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SchemaValidationServiceIntegrationTest {

    private static final String INVENTORY_AVSC = """
        {
          "type": "record",
          "namespace": "com.example.watch.events.inventory",
          "name": "WatchInventoryEvent",
          "fields": [
            {"name": "eventId", "type": "string"},
            {"name": "eventTimestamp", "type": {"type": "long", "logicalType": "timestamp-millis"}},
            {"name": "eventType", "type": {"type": "enum", "name": "InventoryEventType", "symbols": ["CREATED", "UPDATED", "RESERVED", "SOLD", "TRANSFERRED"]}},
            {"name": "watchReference", "type": "string"},
            {"name": "serialNumber", "type": "string"},
            {"name": "materialCode", "type": ["null", "string"], "default": null},
            {"name": "dialCode", "type": ["null", "string"], "default": null},
            {"name": "quantity", "type": "int"},
            {"name": "location", "type": {"type": "record", "name": "InventoryLocation", "fields": [
              {"name": "locationType", "type": "string"},
              {"name": "locationCode", "type": "string"},
              {"name": "countryCode", "type": "string"}
            ]}},
            {"name": "sourceSystem", "type": "string"}
          ]
        }
        """;

    private SchemaRegistryClient schemaRegistryClient;
    private SchemaResultPublisher schemaResultPublisher;
    private SchemaValidationService schemaValidationService;

    @BeforeEach
    void setUp() throws Exception {
        schemaRegistryClient = mock(SchemaRegistryClient.class);
        schemaResultPublisher = mock(SchemaResultPublisher.class);

        when(schemaRegistryClient.getVersionMetadata("watch-demo", "watch-inventory", "1.0.0"))
            .thenReturn(new SchemaVersionMetadata("watch-demo", "watch-inventory", "1.0.0", ArtifactType.AVRO));
        when(schemaRegistryClient.getVersionReferences("watch-demo", "watch-inventory", "1.0.0"))
            .thenReturn(List.of());
        when(schemaRegistryClient.getVersionContent("watch-demo", "watch-inventory", "1.0.0"))
            .thenReturn(INVENTORY_AVSC);

        AppProperties properties = properties();
        var metrics = TestMeters.metrics();
        TopicMappingResolver topicMappingResolver = new TopicMappingResolver(
            properties,
            Caffeine.newBuilder().build(),
            metrics);
        SchemaBundleLoader schemaBundleLoader = new SchemaBundleLoader(
            schemaRegistryClient,
            Caffeine.newBuilder().build(),
            metrics);
        ValidatorService validatorService = new ValidatorService(
            List.of(
                new AvroSchemaValidatorFactory(),
                new JsonSchemaValidatorFactory(tools.jackson.databind.json.JsonMapper.builder().build()),
                new XmlSchemaValidatorFactory()),
            Caffeine.newBuilder().build(),
            metrics);

        schemaValidationService = new SchemaValidationService(
            properties,
            topicMappingResolver,
            new VersionExpressionResolver(),
            schemaBundleLoader,
            validatorService,
            new TopicTemplateRenderer(),
            schemaResultPublisher,
            new TraceIdExtractor(),
            metrics,
            Clock.systemUTC());
    }

    @Test
    void publishesValidatedRouteOnHappyPath() {
        schemaValidationService.handle(message(validInventoryPayload()));

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(schemaResultPublisher).publish(topicCaptor.capture(), any(byte[].class), headersCaptor.capture());
        assertThat(topicCaptor.getValue()).isEqualTo("schema/validated/watch/inventory/update/1.0.0");
        assertThat(headersCaptor.getValue()).containsEntry(SchemaMessageHeaders.VALIDATION_STATUS, RouteKind.VALIDATED.name());
    }

    @Test
    void publishesRejectRouteOnBusinessValidationFailure() {
        schemaValidationService.handle(message(new byte[]{0x01, 0x02}));

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(schemaResultPublisher).publish(topicCaptor.capture(), any(byte[].class), headersCaptor.capture());
        assertThat(topicCaptor.getValue()).isEqualTo("schema/reject/watch/inventory/update/1.0.0");
        assertThat(headersCaptor.getValue()).containsEntry(SchemaMessageHeaders.FAILURE_CATEGORY, FailureCategory.VALIDATION.name());
    }

    @Test
    void setsEquipmentKeyAndPartitionKeyOnValidatedMessage() {
        schemaValidationService.handle(message(validInventoryPayload()));

        ArgumentCaptor<Map<String, Object>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(schemaResultPublisher).publish(any(String.class), any(byte[].class), headersCaptor.capture());
        Map<String, Object> headers = headersCaptor.getValue();
        assertThat(headers).containsEntry(SchemaMessageHeaders.EQUIPMENT_KEY, "watch/inventory/update");
        assertThat(headers).containsEntry(SolaceBinderHeaders.PARTITION_KEY, "watch/inventory/update");
    }

    @Test
    void resolvesSchemaFromMessageHeadersWhenPresent() throws Exception {
        when(schemaRegistryClient.getVersionMetadata("watch-demo", "watch-inventory", "1.0.0"))
            .thenReturn(new SchemaVersionMetadata("watch-demo", "watch-inventory", "1.0.0", ArtifactType.AVRO));

        Message<byte[]> msg = MessageBuilder.withPayload(validInventoryPayload())
            .setHeader(SolaceHeaders.DESTINATION, "some/unmapped/topic")
            .setHeader(SchemaValidationService.HEADER_SCHEMA_GROUP, "watch-demo")
            .setHeader(SchemaValidationService.HEADER_SCHEMA_NAME, "watch-inventory")
            .setHeader(SchemaValidationService.HEADER_SCHEMA_VERSION, "1.0.0")
            .build();

        schemaValidationService.handle(msg);

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Map<String, Object>> headersCaptor = ArgumentCaptor.forClass(Map.class);
        verify(schemaResultPublisher).publish(topicCaptor.capture(), any(byte[].class), headersCaptor.capture());
        assertThat(topicCaptor.getValue()).isEqualTo("schema/validated/some/unmapped/topic");
        assertThat(headersCaptor.getValue())
            .containsEntry(SchemaMessageHeaders.VALIDATION_STATUS, RouteKind.VALIDATED.name())
            .containsEntry(SchemaMessageHeaders.SCHEMA_GROUP, "watch-demo")
            .containsEntry(SchemaMessageHeaders.SCHEMA_ARTIFACT, "watch-inventory")
            .containsEntry(SchemaMessageHeaders.MAPPING_ID, "header-declared");
    }

    @Test
    void publishesProcessingErrorWhenRegistryFails() {
        when(schemaRegistryClient.getVersionMetadata("watch-demo", "watch-inventory", "1.0.0"))
            .thenThrow(new RuntimeException("registry unavailable"));

        schemaValidationService.handle(message(validInventoryPayload()));

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        verify(schemaResultPublisher).publish(topicCaptor.capture(), any(byte[].class), anyMap());
        assertThat(topicCaptor.getValue()).isEqualTo("schema/error/watch/inventory/update/1.0.0");
    }

    @Test
    void skipsMessagesAlreadyProcessedByTheMi() {
        Message<byte[]> msg = MessageBuilder.withPayload(validInventoryPayload())
            .setHeader(SolaceHeaders.DESTINATION, "watch/inventory/update/1.0.0")
            .setHeader(SchemaMessageHeaders.VALIDATION_STATUS, RouteKind.VALIDATED.name())
            .build();

        schemaValidationService.handle(msg);

        verify(schemaResultPublisher, never()).publish(anyString(), any(byte[].class), anyMap());
        verify(schemaRegistryClient, never()).getVersionMetadata(anyString(), anyString(), anyString());
    }

    private AppProperties properties() {
        AppProperties properties = new AppProperties();
        properties.getIngress().setQueueName("schema-validation/test");
        properties.getRouting().setValidatedTopicTemplate("schema/validated/{originalTopic}");
        properties.getRouting().setRejectTopicTemplate("schema/reject/{originalTopic}");
        properties.getRouting().setProcessingErrorTopicTemplate("schema/error/{originalTopic}");

        AppProperties.Mapping mapping = new AppProperties.Mapping();
        mapping.setId("inventory-v1");
        mapping.setTopicPattern("watch/inventory/update/{version}");
        mapping.setSchemaGroup("watch-demo");
        mapping.setArtifactId("watch-inventory");
        mapping.setArtifactType(ArtifactType.AVRO);
        AppProperties.VersionStrategy strategy = new AppProperties.VersionStrategy();
        strategy.setType(com.solace.schemavalidator.resolution.VersionStrategyType.TOPIC_CAPTURE);
        strategy.setCapture("version");
        mapping.setVersionStrategy(strategy);
        properties.setMappings(List.of(mapping));
        return properties;
    }

    private Message<byte[]> message(byte[] payload) {
        return MessageBuilder.withPayload(payload)
            .setHeader(SolaceHeaders.DESTINATION, "watch/inventory/update/1.0.0")
            .build();
    }

    private byte[] validInventoryPayload() {
        try {
            Schema schema = new Schema.Parser().parse(INVENTORY_AVSC);
            Schema locationSchema = schema.getField("location").schema();
            GenericData.Record location = new GenericData.Record(locationSchema);
            location.put("locationType", "BOUTIQUE");
            location.put("locationCode", "PAR01");
            location.put("countryCode", "FR");

            GenericData.Record record = new GenericData.Record(schema);
            record.put("eventId", "evt-1");
            record.put("eventTimestamp", 1742897730000L);
            record.put("eventType", new GenericData.EnumSymbol(schema.getField("eventType").schema(), "UPDATED"));
            record.put("watchReference", "126610LN");
            record.put("serialNumber", "SN12345");
            record.put("materialCode", "OYS");
            record.put("dialCode", "BLK");
            record.put("quantity", 2);
            record.put("location", location);
            record.put("sourceSystem", "ERP");

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            GenericDatumWriter<GenericData.Record> writer = new GenericDatumWriter<>(schema);
            var encoder = EncoderFactory.get().binaryEncoder(outputStream, null);
            writer.write(record, encoder);
            encoder.flush();
            return outputStream.toByteArray();
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}
