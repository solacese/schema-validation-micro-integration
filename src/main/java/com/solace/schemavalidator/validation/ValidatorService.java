package com.solace.schemavalidator.validation;

import com.github.benmanes.caffeine.cache.Cache;
import com.solace.schemavalidator.observability.SchemaValidationMetrics;
import com.solace.schemavalidator.registry.SchemaBundle;
import com.solace.schemavalidator.registry.SchemaVersionCoordinate;
import com.solace.schemavalidator.resolution.ArtifactType;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class ValidatorService {

    private final Map<ArtifactType, ValidatorFactory> factories;
    private final Cache<SchemaVersionCoordinate, CompiledValidator> cache;
    private final SchemaValidationMetrics metrics;

    public ValidatorService(List<ValidatorFactory> factories,
                            Cache<SchemaVersionCoordinate, CompiledValidator> cache,
                            SchemaValidationMetrics metrics) {
        this.cache = cache;
        this.metrics = metrics;
        this.factories = new EnumMap<>(ArtifactType.class);
        for (ValidatorFactory factory : factories) {
            this.factories.put(factory.supportedType(), factory);
        }
    }

    public CompiledValidator getValidator(SchemaBundle schemaBundle) {
        SchemaVersionCoordinate coordinate = schemaBundle.root().coordinate();
        CompiledValidator cached = cache.getIfPresent(coordinate);
        if (cached != null) {
            metrics.recordValidatorCacheHit();
            return cached;
        }
        metrics.recordValidatorCacheMiss();
        ArtifactType artifactType = schemaBundle.root().artifactType();
        if (artifactType == ArtifactType.UNKNOWN) {
            throw new IllegalStateException(
                "Cannot validate artifact '" + schemaBundle.root().coordinate() + "': the registry returned an unrecognized artifact type");
        }
        ValidatorFactory factory = factories.get(artifactType);
        if (factory == null) {
            throw new IllegalStateException("No validator registered for artifact type " + artifactType);
        }
        CompiledValidator validator = factory.compile(schemaBundle);
        cache.put(coordinate, validator);
        return validator;
    }
}
