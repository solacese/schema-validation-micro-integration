package com.solace.schemavalidator.validation;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.solace.schemavalidator.TestMeters;
import com.solace.schemavalidator.registry.SchemaBundle;
import com.solace.schemavalidator.registry.SchemaDocument;
import com.solace.schemavalidator.registry.SchemaVersionCoordinate;
import com.solace.schemavalidator.resolution.ArtifactType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ValidatorServiceCacheTest {

    @Test
    void reusesCompiledValidatorFromCache() {
        ValidatorFactory factory = new ValidatorFactory() {
            @Override
            public ArtifactType supportedType() {
                return ArtifactType.AVRO;
            }

            @Override
            public CompiledValidator compile(SchemaBundle schemaBundle) {
                return payload -> ValidationOutcome.success();
            }
        };

        ValidatorService service = new ValidatorService(
            List.of(factory),
            Caffeine.newBuilder().build(),
            TestMeters.metrics());

        SchemaDocument document = new SchemaDocument(
            new SchemaVersionCoordinate("watch-demo", "watch-inventory", "1.0.0"),
            ArtifactType.AVRO,
            "{}",
            List.of());
        SchemaBundle bundle = new SchemaBundle(document, Map.of(document.coordinate(), document), List.of(document));

        CompiledValidator first = service.getValidator(bundle);
        CompiledValidator second = service.getValidator(bundle);

        assertThat(second).isSameAs(first);
    }
}
