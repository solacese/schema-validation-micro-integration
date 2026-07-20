package com.solace.schemavalidator.observability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

@Component
public class SchemaValidationMetrics {

    private final Counter validCounter;
    private final Counter invalidCounter;
    private final Counter registryErrorCounter;
    private final Counter processingErrorCounter;
    private final Counter topicResolutionCacheHitCounter;
    private final Counter topicResolutionCacheMissCounter;
    private final Counter schemaBundleCacheHitCounter;
    private final Counter schemaBundleCacheMissCounter;
    private final Counter validatorCacheHitCounter;
    private final Counter validatorCacheMissCounter;
    private final Counter payloadTooLargeCounter;
    private final Counter validationTimeoutCounter;
    private final Timer validationLatencyTimer;
    private final DistributionSummary payloadSizeSummary;

    public SchemaValidationMetrics(MeterRegistry meterRegistry) {
        this.validCounter = meterRegistry.counter("schema.validation.mi.validation.valid");
        this.invalidCounter = meterRegistry.counter("schema.validation.mi.validation.invalid");
        this.registryErrorCounter = meterRegistry.counter("schema.validation.mi.validation.registry.errors");
        this.processingErrorCounter = meterRegistry.counter("schema.validation.mi.validation.processing.errors");
        this.topicResolutionCacheHitCounter = meterRegistry.counter("schema.validation.mi.cache.topic.hit");
        this.topicResolutionCacheMissCounter = meterRegistry.counter("schema.validation.mi.cache.topic.miss");
        this.schemaBundleCacheHitCounter = meterRegistry.counter("schema.validation.mi.cache.schema.hit");
        this.schemaBundleCacheMissCounter = meterRegistry.counter("schema.validation.mi.cache.schema.miss");
        this.validatorCacheHitCounter = meterRegistry.counter("schema.validation.mi.cache.validator.hit");
        this.validatorCacheMissCounter = meterRegistry.counter("schema.validation.mi.cache.validator.miss");
        this.payloadTooLargeCounter = meterRegistry.counter("schema.validation.mi.validation.payload.too_large");
        this.validationTimeoutCounter = meterRegistry.counter("schema.validation.mi.validation.timeout");
        this.validationLatencyTimer = Timer.builder("schema.validation.mi.validation.latency")
            .description("End-to-end message validation latency")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(meterRegistry);
        this.payloadSizeSummary = DistributionSummary.builder("schema.validation.mi.message.payload.size")
            .description("Inbound message payload size in bytes")
            .baseUnit("bytes")
            .publishPercentiles(0.5, 0.95, 0.99)
            .register(meterRegistry);
    }

    public void recordValid() { validCounter.increment(); }
    public void recordInvalid() { invalidCounter.increment(); }
    public void recordRegistryError() { registryErrorCounter.increment(); }
    public void recordProcessingError() { processingErrorCounter.increment(); }
    public void recordTopicResolutionCacheHit() { topicResolutionCacheHitCounter.increment(); }
    public void recordTopicResolutionCacheMiss() { topicResolutionCacheMissCounter.increment(); }
    public void recordSchemaBundleCacheHit() { schemaBundleCacheHitCounter.increment(); }
    public void recordSchemaBundleCacheMiss() { schemaBundleCacheMissCounter.increment(); }
    public void recordValidatorCacheHit() { validatorCacheHitCounter.increment(); }
    public void recordValidatorCacheMiss() { validatorCacheMissCounter.increment(); }
    public void recordPayloadTooLarge() { payloadTooLargeCounter.increment(); }
    public void recordValidationTimeout() { validationTimeoutCounter.increment(); }
    public Timer getValidationLatencyTimer() { return validationLatencyTimer; }
    public void recordPayloadSize(long bytes) { payloadSizeSummary.record(bytes); }
}
