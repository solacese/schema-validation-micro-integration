package com.solace.schemavalidator.messaging;

import com.solace.schemavalidator.config.AppProperties;
import com.solace.schemavalidator.observability.SchemaValidationMetrics;
import com.solace.schemavalidator.observability.TraceIdExtractor;
import com.solace.schemavalidator.registry.SchemaBundle;
import com.solace.schemavalidator.registry.SchemaBundleLoader;
import com.solace.schemavalidator.registry.SchemaRegistryException;
import com.solace.schemavalidator.resolution.ArtifactType;
import com.solace.schemavalidator.resolution.ResolvedTopicMapping;
import com.solace.schemavalidator.resolution.SchemaResolutionException;
import com.solace.schemavalidator.resolution.TopicMappingResolver;
import com.solace.schemavalidator.resolution.TopicTemplateRenderer;
import com.solace.schemavalidator.resolution.VersionExpressionResolver;
import com.solace.schemavalidator.validation.CompiledValidator;
import com.solace.schemavalidator.validation.ValidationOutcome;
import com.solace.schemavalidator.validation.ValidatorService;
import io.micrometer.core.instrument.Timer;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.Message;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import com.solacesystems.jcsmp.Destination;
import com.solacesystems.jcsmp.XMLMessage;
import com.solace.spring.cloud.stream.binder.messaging.SolaceBinderHeaders;
import com.solace.spring.cloud.stream.binder.messaging.SolaceHeaders;
import java.nio.charset.StandardCharsets;

@Service
public class SchemaValidationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(SchemaValidationService.class);
    private static final int FAILURE_REASON_MAX_LENGTH = 512;
    static final String HEADER_SCHEMA_GROUP = "schema_group";
    static final String HEADER_SCHEMA_NAME = "schema_name";
    static final String HEADER_SCHEMA_VERSION = "schema_version";

    private final AppProperties properties;
    private final TopicMappingResolver topicMappingResolver;
    private final VersionExpressionResolver versionExpressionResolver;
    private final SchemaBundleLoader schemaBundleLoader;
    private final ValidatorService validatorService;
    private final TopicTemplateRenderer topicTemplateRenderer;
    private final SchemaResultPublisher schemaResultPublisher;
    private final TraceIdExtractor traceIdExtractor;
    private final SchemaValidationMetrics metrics;
    private final Clock clock;
    private final int maxPayloadBytes;
    private final long validationTimeoutMs;
    private final ExecutorService validationExecutor;

    public SchemaValidationService(AppProperties properties,
                                   TopicMappingResolver topicMappingResolver,
                                   VersionExpressionResolver versionExpressionResolver,
                                   SchemaBundleLoader schemaBundleLoader,
                                   ValidatorService validatorService,
                                   TopicTemplateRenderer topicTemplateRenderer,
                                   SchemaResultPublisher schemaResultPublisher,
                                   TraceIdExtractor traceIdExtractor,
                                   SchemaValidationMetrics metrics,
                                   Clock clock) {
        this.properties = properties;
        this.topicMappingResolver = topicMappingResolver;
        this.versionExpressionResolver = versionExpressionResolver;
        this.schemaBundleLoader = schemaBundleLoader;
        this.validatorService = validatorService;
        this.topicTemplateRenderer = topicTemplateRenderer;
        this.schemaResultPublisher = schemaResultPublisher;
        this.traceIdExtractor = traceIdExtractor;
        this.metrics = metrics;
        this.clock = clock;
        this.maxPayloadBytes = properties.getMaxPayloadBytes();
        this.validationTimeoutMs = properties.getValidationTimeout().toMillis();
        this.validationExecutor = Executors.newFixedThreadPool(Math.max(2, Runtime.getRuntime().availableProcessors()), task -> {
            Thread thread = new Thread(task, "schema-validation-worker");
            thread.setDaemon(true);
            return thread;
        });
    }

    @PreDestroy
    public void shutdown() {
        validationExecutor.shutdownNow();
    }

    public void handle(Message<?> message) {
        Timer.Sample timerSample = Timer.start();
        String traceId = traceIdExtractor.extractOrCreate(message);
        String originalTopic = "unknown";
        try {
            originalTopic = extractOriginalTopic(message);
            if (StringUtils.hasText(headerString(message, SchemaMessageHeaders.VALIDATION_STATUS))) {
                LOGGER.debug("event=already_validated_skipped traceId={} topic={}", traceId, originalTopic);
                return;
            }

            byte[] payload = extractPayloadBytes(message.getPayload());
            metrics.recordPayloadSize(payload.length);

            if (payload.length > maxPayloadBytes) {
                metrics.recordPayloadTooLarge();
                metrics.recordInvalid();
                String reason = String.format("Payload size %d bytes exceeds maximum %d bytes", payload.length, maxPayloadBytes);
                ResolvedTopicMapping synthetic = new ResolvedTopicMapping(new AppProperties.Mapping(), Map.of(), originalTopic);
                publish(RouteKind.REJECT, synthetic, "unknown", payload, traceId, FailureCategory.VALIDATION, reason);
                log("rejected", synthetic, "unknown", traceId, reason);
                return;
            }

            // Resolve schema: prefer explicit headers from publisher, fall back to topic-based mapping
            ResolvedTopicMapping resolvedTopicMapping;
            String versionExpression;
            String headerGroup = headerString(message, HEADER_SCHEMA_GROUP);
            String headerName = headerString(message, HEADER_SCHEMA_NAME);
            String headerVersion = headerString(message, HEADER_SCHEMA_VERSION);

            if (StringUtils.hasText(headerGroup) && StringUtils.hasText(headerName)) {
                // Publisher declared schema — build synthetic mapping from headers
                AppProperties.Mapping headerMapping = new AppProperties.Mapping();
                headerMapping.setId("header-declared");
                headerMapping.setSchemaGroup(headerGroup);
                headerMapping.setArtifactId(headerName);
                headerMapping.setArtifactType(ArtifactType.UNKNOWN);
                resolvedTopicMapping = new ResolvedTopicMapping(headerMapping, Map.of(), originalTopic);
                versionExpression = StringUtils.hasText(headerVersion) ? headerVersion : "latest-enabled";
                LOGGER.debug("event=header_resolved topic={} schema={}/{} version={}", originalTopic, headerGroup, headerName, versionExpression);
            } else {
                // Fall back to topic-based resolution
                resolvedTopicMapping = topicMappingResolver.resolve(originalTopic);
                versionExpression = versionExpressionResolver.resolve(resolvedTopicMapping);
            }

            SchemaBundle schemaBundle = schemaBundleLoader.load(
                resolvedTopicMapping.mapping().getSchemaGroup(),
                resolvedTopicMapping.mapping().getArtifactId(),
                versionExpression);
            if ("header-declared".equals(resolvedTopicMapping.mapping().getId())) {
                // Publishers declare a coordinate; the registry owns its format.
                resolvedTopicMapping.mapping().setArtifactType(schemaBundle.root().artifactType());
            }
            CompiledValidator validator = validatorService.getValidator(schemaBundle);

            ValidationOutcome outcome = validateWithTimeout(validator, payload);

            if (outcome.valid()) {
                metrics.recordValid();
                publish(RouteKind.VALIDATED, resolvedTopicMapping, schemaBundle.root().coordinate().version(),
                    payload, traceId, FailureCategory.NONE, null);
                log("validated", resolvedTopicMapping, schemaBundle.root().coordinate().version(), traceId, null);
                return;
            }
            metrics.recordInvalid();
            publish(RouteKind.REJECT, resolvedTopicMapping, schemaBundle.root().coordinate().version(),
                payload, traceId, FailureCategory.VALIDATION, outcome.reason());
            log("rejected", resolvedTopicMapping, schemaBundle.root().coordinate().version(), traceId, outcome.reason());
        } catch (SchemaResolutionException ex) {
            metrics.recordProcessingError();
            publishProcessingError(extractPayloadBytes(message.getPayload()), originalTopic, traceId, null, null, FailureCategory.MAPPING, ex.getMessage());
        } catch (SchemaRegistryException ex) {
            metrics.recordRegistryError();
            publishProcessingError(extractPayloadBytes(message.getPayload()), originalTopic, traceId, null, null, FailureCategory.REGISTRY, ex.getMessage());
        } catch (RuntimeException ex) {
            metrics.recordProcessingError();
            publishProcessingError(extractPayloadBytes(message.getPayload()), originalTopic, traceId, null, null, FailureCategory.PROCESSING, ex.getMessage());
        } finally {
            timerSample.stop(metrics.getValidationLatencyTimer());
        }
    }

    private ValidationOutcome validateWithTimeout(CompiledValidator validator, byte[] payload) {
        Future<ValidationOutcome> future = validationExecutor.submit(() -> validator.validate(payload));
        try {
            return future.get(validationTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            metrics.recordValidationTimeout();
            throw new RuntimeException("Validation timed out after " + validationTimeoutMs + "ms");
        } catch (ExecutionException e) {
            throw new RuntimeException(e.getCause());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new RuntimeException("Validation interrupted", e);
        }
    }

    private void publish(RouteKind routeKind,
                         ResolvedTopicMapping resolvedTopicMapping,
                         String schemaVersion,
                         byte[] payload,
                         String traceId,
                         FailureCategory failureCategory,
                         String failureReason) {
        String destination = routeTopic(routeKind, resolvedTopicMapping, schemaVersion);
        Map<String, Object> headers = buildHeaders(routeKind, resolvedTopicMapping, schemaVersion, traceId, failureCategory, failureReason);
        try {
            schemaResultPublisher.publish(destination, payload, headers);
        } catch (RuntimeException ex) {
            if (routeKind == RouteKind.PROCESSING_ERROR) {
                throw ex;
            }
            metrics.recordProcessingError();
            publishProcessingError(payload, resolvedTopicMapping.originalTopic(), traceId,
                resolvedTopicMapping.mapping().getSchemaGroup(),
                resolvedTopicMapping.mapping().getArtifactId(),
                FailureCategory.PUBLISH, ex.getMessage());
        }
    }

    private void publishProcessingError(byte[] payload,
                                        String originalTopic,
                                        String traceId,
                                        String schemaGroup,
                                        String schemaArtifact,
                                        FailureCategory failureCategory,
                                        String failureReason) {
        ResolvedTopicMapping synthetic = new ResolvedTopicMapping(new AppProperties.Mapping(), Map.of(), originalTopic);
        String destination = topicTemplateRenderer.render(properties.getRouting().getProcessingErrorTopicTemplate(), synthetic, "unknown",
            outcomeLabel(RouteKind.PROCESSING_ERROR));
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put(SchemaMessageHeaders.VALIDATION_STATUS, RouteKind.PROCESSING_ERROR.name());
        headers.put(SchemaMessageHeaders.SCHEMA_GROUP, nullSafe(schemaGroup));
        headers.put(SchemaMessageHeaders.SCHEMA_ARTIFACT, nullSafe(schemaArtifact));
        headers.put(SchemaMessageHeaders.SCHEMA_VERSION, "unknown");
        headers.put(SchemaMessageHeaders.SCHEMA_TYPE, "unknown");
        headers.put(SchemaMessageHeaders.VALIDATION_TIMESTAMP, Instant.now(clock).toString());
        headers.put(SchemaMessageHeaders.FAILURE_CATEGORY, failureCategory.name());
        headers.put(SchemaMessageHeaders.FAILURE_REASON, truncate(failureReason));
        headers.put(SchemaMessageHeaders.ORIGINAL_TOPIC, originalTopic);
        headers.put(SchemaMessageHeaders.MAPPING_ID, "unresolved");
        headers.put(TraceIdExtractor.TRACE_ID_HEADER, traceId);
        String equipmentKey = extractEquipmentKey(originalTopic);
        if (equipmentKey != null) {
            headers.put(SolaceBinderHeaders.PARTITION_KEY, equipmentKey);
            headers.put(SchemaMessageHeaders.EQUIPMENT_KEY, equipmentKey);
        }
        schemaResultPublisher.publish(destination, payload, headers);
        LOGGER.error("event=processing_error traceId={} originalTopic={} reason={}", traceId, originalTopic, truncate(failureReason));
    }

    private Map<String, Object> buildHeaders(RouteKind routeKind,
                                             ResolvedTopicMapping resolvedTopicMapping,
                                             String schemaVersion,
                                             String traceId,
                                             FailureCategory failureCategory,
                                             String failureReason) {
        Map<String, Object> headers = new LinkedHashMap<>();
        headers.put(SchemaMessageHeaders.VALIDATION_STATUS, routeKind.name());
        headers.put(SchemaMessageHeaders.SCHEMA_GROUP, resolvedTopicMapping.mapping().getSchemaGroup());
        headers.put(SchemaMessageHeaders.SCHEMA_ARTIFACT, resolvedTopicMapping.mapping().getArtifactId());
        headers.put(SchemaMessageHeaders.SCHEMA_VERSION, schemaVersion);
        headers.put(SchemaMessageHeaders.SCHEMA_TYPE, resolvedTopicMapping.mapping().getArtifactType().getRegistryType());
        headers.put(SchemaMessageHeaders.VALIDATION_TIMESTAMP, Instant.now(clock).toString());
        headers.put(SchemaMessageHeaders.FAILURE_CATEGORY, failureCategory.name());
        headers.put(SchemaMessageHeaders.FAILURE_REASON, truncate(failureReason));
        headers.put(SchemaMessageHeaders.ORIGINAL_TOPIC, resolvedTopicMapping.originalTopic());
        headers.put(SchemaMessageHeaders.MAPPING_ID, resolvedTopicMapping.mapping().getId());
        headers.put(TraceIdExtractor.TRACE_ID_HEADER, traceId);
        String equipmentKey = extractEquipmentKey(resolvedTopicMapping.originalTopic());
        if (equipmentKey != null) {
            headers.put(SolaceBinderHeaders.PARTITION_KEY, equipmentKey);
            headers.put(SchemaMessageHeaders.EQUIPMENT_KEY, equipmentKey);
        }
        return headers;
    }

    private String routeTopic(RouteKind routeKind, ResolvedTopicMapping resolvedTopicMapping, String schemaVersion) {
        String outcomeLabel = outcomeLabel(routeKind);
        return switch (routeKind) {
            case VALIDATED -> topicTemplateRenderer.render(properties.getRouting().getValidatedTopicTemplate(), resolvedTopicMapping, schemaVersion, outcomeLabel);
            case REJECT -> topicTemplateRenderer.render(properties.getRouting().getRejectTopicTemplate(), resolvedTopicMapping, schemaVersion, outcomeLabel);
            case PROCESSING_ERROR -> topicTemplateRenderer.render(properties.getRouting().getProcessingErrorTopicTemplate(), resolvedTopicMapping, schemaVersion, outcomeLabel);
        };
    }

    private String outcomeLabel(RouteKind routeKind) {
        AppProperties.OutcomeLabels labels = properties.getRouting().getOutcomeLabels();
        return switch (routeKind) {
            case VALIDATED -> labels.getValidated();
            case REJECT -> labels.getReject();
            case PROCESSING_ERROR -> labels.getProcessingError();
        };
    }

    private String headerString(Message<?> message, String key) {
        Object value = message.getHeaders().get(key);
        return value instanceof String s ? s : null;
    }

    private String extractEquipmentKey(String originalTopic) {
        if (originalTopic == null) return null;
        String[] segments = originalTopic.split("/");
        return segments.length >= 3
            ? segments[0] + "/" + segments[1] + "/" + segments[2]
            : null;
    }

    private String extractOriginalTopic(Message<?> message) {
        Object topic = message.getHeaders().get(SolaceHeaders.DESTINATION);
        if (topic == null) {
            topic = message.getHeaders().get("solace_destination");
        }
        if (topic instanceof Destination destination && StringUtils.hasText(destination.getName())) {
            return destination.getName();
        }
        if (topic instanceof String stringValue && StringUtils.hasText(stringValue)) {
            return stringValue;
        }
        Object sourceData = message.getHeaders().get("sourceData");
        if (sourceData instanceof XMLMessage xmlMessage) {
            Destination sourceDestination = xmlMessage.getDestination();
            if (sourceDestination != null && StringUtils.hasText(sourceDestination.getName())) {
                return sourceDestination.getName();
            }
        }
        throw new SchemaResolutionException("Inbound message did not include a source topic header");
    }

    private byte[] extractPayloadBytes(Object payload) {
        if (payload instanceof byte[] bytes) {
            return bytes;
        }
        if (payload instanceof String stringPayload) {
            return stringPayload.getBytes(StandardCharsets.UTF_8);
        }
        throw new IllegalArgumentException("Unsupported inbound payload type: " + (payload == null ? "null" : payload.getClass().getName()));
    }

    private void log(String event,
                     ResolvedTopicMapping resolvedTopicMapping,
                     String schemaVersion,
                     String traceId,
                     String reason) {
        if (reason == null) {
            LOGGER.info("event={} traceId={} topic={} mappingId={} schema={}/{} version={}",
                event,
                traceId,
                resolvedTopicMapping.originalTopic(),
                resolvedTopicMapping.mapping().getId(),
                resolvedTopicMapping.mapping().getSchemaGroup(),
                resolvedTopicMapping.mapping().getArtifactId(),
                schemaVersion);
            return;
        }
        LOGGER.info("event={} traceId={} topic={} mappingId={} schema={}/{} version={} reason={}",
            event,
            traceId,
            resolvedTopicMapping.originalTopic(),
            resolvedTopicMapping.mapping().getId(),
            resolvedTopicMapping.mapping().getSchemaGroup(),
            resolvedTopicMapping.mapping().getArtifactId(),
            schemaVersion,
            truncate(reason));
    }

    private String truncate(String value) {
        if (!StringUtils.hasText(value)) {
            return "";
        }
        if (value.length() <= FAILURE_REASON_MAX_LENGTH) {
            return value;
        }
        return value.substring(0, FAILURE_REASON_MAX_LENGTH);
    }

    private String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
