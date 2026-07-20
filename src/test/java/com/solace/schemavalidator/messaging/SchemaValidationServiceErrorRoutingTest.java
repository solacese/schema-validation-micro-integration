package com.solace.schemavalidator.messaging;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.schemavalidator.TestMeters;
import com.solace.schemavalidator.config.AppProperties;
import com.solace.schemavalidator.observability.TraceIdExtractor;
import com.solace.schemavalidator.registry.SchemaBundleLoader;
import com.solace.schemavalidator.registry.SchemaRegistryClient;
import com.solace.schemavalidator.registry.SchemaRegistryException;
import com.solace.schemavalidator.registry.SchemaVersionMetadata;
import com.solace.schemavalidator.resolution.ArtifactType;
import com.solace.schemavalidator.resolution.TopicMappingResolver;
import com.solace.schemavalidator.resolution.TopicTemplateRenderer;
import com.solace.schemavalidator.resolution.VersionExpressionResolver;
import com.solace.schemavalidator.validation.AvroSchemaValidatorFactory;
import com.solace.schemavalidator.validation.JsonSchemaValidatorFactory;
import com.solace.schemavalidator.validation.ValidatorService;
import com.solace.schemavalidator.validation.XmlSchemaValidatorFactory;
import com.solace.spring.cloud.stream.binder.messaging.SolaceHeaders;
import java.time.Clock;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SchemaValidationServiceErrorRoutingTest {

    private SchemaRegistryClient schemaRegistryClient;
    private SchemaResultPublisher schemaResultPublisher;
    private SchemaValidationService schemaValidationService;

    @BeforeEach
    void setUp() {
        schemaRegistryClient = mock(SchemaRegistryClient.class);
        schemaResultPublisher = mock(SchemaResultPublisher.class);

        AppProperties properties = properties();
        var metrics = TestMeters.metrics();
        schemaValidationService = new SchemaValidationService(
            properties,
            new TopicMappingResolver(properties, Caffeine.newBuilder().build(), metrics),
            new VersionExpressionResolver(),
            new SchemaBundleLoader(schemaRegistryClient, Caffeine.newBuilder().build(), metrics),
            new ValidatorService(
                List.of(new AvroSchemaValidatorFactory(),
                    new JsonSchemaValidatorFactory(new ObjectMapper()),
                    new XmlSchemaValidatorFactory()),
                Caffeine.newBuilder().build(),
                metrics),
            new TopicTemplateRenderer(),
            schemaResultPublisher,
            new TraceIdExtractor(),
            metrics,
            Clock.systemUTC());
    }

    @Test
    void publishesMappingErrorWhenTopicHasNoMatch() {
        Message<byte[]> message = MessageBuilder.withPayload(new byte[0])
            .setHeader(SolaceHeaders.DESTINATION, "completely/unknown/topic")
            .build();

        schemaValidationService.handle(message);

        ArgumentCaptor<String> topicCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<java.util.Map<String, Object>> headersCaptor = ArgumentCaptor.forClass(java.util.Map.class);
        verify(schemaResultPublisher).publish(topicCaptor.capture(), any(byte[].class), headersCaptor.capture());

        assertThat(topicCaptor.getValue()).startsWith("schema/error/");
        assertThat(headersCaptor.getValue())
            .containsEntry(SchemaMessageHeaders.FAILURE_CATEGORY, FailureCategory.MAPPING.name());
    }

    @Test
    void publishesRegistryErrorWhenRegistryThrowsSchemaRegistryException() {
        when(schemaRegistryClient.getVersionMetadata(anyString(), anyString(), anyString()))
            .thenThrow(new SchemaRegistryException("registry down"));

        Message<byte[]> message = MessageBuilder.withPayload(new byte[0])
            .setHeader(SolaceHeaders.DESTINATION, "watch/boutique/transfer/main/eu")
            .build();

        schemaValidationService.handle(message);

        ArgumentCaptor<java.util.Map<String, Object>> headersCaptor = ArgumentCaptor.forClass(java.util.Map.class);
        verify(schemaResultPublisher).publish(anyString(), any(byte[].class), headersCaptor.capture());

        assertThat(headersCaptor.getValue())
            .containsEntry(SchemaMessageHeaders.FAILURE_CATEGORY, FailureCategory.REGISTRY.name());
    }

    @Test
    void publishesProcessingErrorWhenMessageHasNoTopic() {
        // No destination header → SchemaResolutionException from extractOriginalTopic
        Message<byte[]> message = MessageBuilder.withPayload(new byte[0]).build();

        schemaValidationService.handle(message);

        ArgumentCaptor<java.util.Map<String, Object>> headersCaptor = ArgumentCaptor.forClass(java.util.Map.class);
        verify(schemaResultPublisher).publish(anyString(), any(byte[].class), headersCaptor.capture());

        assertThat(headersCaptor.getValue())
            .containsEntry(SchemaMessageHeaders.FAILURE_CATEGORY, FailureCategory.MAPPING.name());
    }

    private AppProperties properties() {
        AppProperties properties = new AppProperties();
        properties.getIngress().setQueueName("schema-validation/test");
        properties.getRouting().setValidatedTopicTemplate("schema/validated/{originalTopic}");
        properties.getRouting().setRejectTopicTemplate("schema/reject/{originalTopic}");
        properties.getRouting().setProcessingErrorTopicTemplate("schema/error/{originalTopic}");

        AppProperties.Mapping mapping = new AppProperties.Mapping();
        mapping.setId("boutique-transfer-fixed");
        mapping.setTopicPattern("watch/boutique/transfer/{branch}/{region}");
        mapping.setSchemaGroup("watch-demo");
        mapping.setArtifactId("boutique-transfer");
        mapping.setArtifactType(ArtifactType.XSD);
        AppProperties.VersionStrategy strategy = new AppProperties.VersionStrategy();
        strategy.setType(com.solace.schemavalidator.resolution.VersionStrategyType.FIXED);
        strategy.setValue("1.0.0");
        mapping.setVersionStrategy(strategy);
        properties.setMappings(List.of(mapping));
        return properties;
    }
}
