package com.solace.schemavalidator.resolution;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.solace.schemavalidator.TestMeters;
import com.solace.schemavalidator.config.AppProperties;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TopicPatternResolverTest {

    @Test
    void matchesCapturePatternAndCachesResolvedMapping() {
        AppProperties.Mapping mapping = new AppProperties.Mapping();
        mapping.setId("inventory");
        mapping.setTopicPattern("watch/inventory/update/{version}");
        mapping.setSchemaGroup("watch-demo");
        mapping.setArtifactId("watch-inventory");
        mapping.setArtifactType(ArtifactType.AVRO);

        AppProperties properties = new AppProperties();
        properties.setMappings(List.of(mapping));

        TopicMappingResolver resolver = new TopicMappingResolver(
            properties,
            Caffeine.newBuilder().build(),
            TestMeters.metrics());

        ResolvedTopicMapping first = resolver.resolve("watch/inventory/update/1.0.0");
        ResolvedTopicMapping second = resolver.resolve("watch/inventory/update/1.0.0");

        assertThat(first.captures()).containsEntry("version", "1.0.0");
        assertThat(second).isSameAs(first);
    }

    @Test
    void failsSafelyOnAmbiguousMatch() {
        AppProperties.Mapping first = new AppProperties.Mapping();
        first.setId("one");
        first.setTopicPattern("watch/{domain}/{version}");
        first.setSchemaGroup("watch-demo");
        first.setArtifactId("one");
        first.setArtifactType(ArtifactType.AVRO);

        AppProperties.Mapping second = new AppProperties.Mapping();
        second.setId("two");
        second.setTopicPattern("watch/inventory/{token}");
        second.setSchemaGroup("watch-demo");
        second.setArtifactId("two");
        second.setArtifactType(ArtifactType.AVRO);

        AppProperties properties = new AppProperties();
        properties.setMappings(List.of(first, second));

        TopicMappingResolver resolver = new TopicMappingResolver(
            properties,
            Caffeine.newBuilder().build(),
            TestMeters.metrics());

        assertThatThrownBy(() -> resolver.resolve("watch/inventory/1.0.0"))
            .isInstanceOf(SchemaResolutionException.class)
            .hasMessageContaining("Ambiguous");
    }

    @Test
    void rendersTopicsFromTemplate() {
        AppProperties.Mapping mapping = new AppProperties.Mapping();
        mapping.setId("inventory");
        ResolvedTopicMapping resolved = new ResolvedTopicMapping(mapping, Map.of("region", "eu"), "watch/inventory/update/1.0.0");

        TopicTemplateRenderer renderer = new TopicTemplateRenderer();

        assertThat(renderer.render("schema/validated/{originalTopic}", resolved, "1.0.0"))
            .isEqualTo("schema/validated/watch/inventory/update/1.0.0");
    }
}
