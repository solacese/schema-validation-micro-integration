package com.solace.schemavalidator.resolution;

import com.solace.schemavalidator.config.AppProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VersionExpressionResolverErrorTest {

    private final VersionExpressionResolver resolver = new VersionExpressionResolver();

    @Test
    void throwsWhenTopicCaptureNameIsBlank() {
        AppProperties.Mapping mapping = new AppProperties.Mapping();
        mapping.setId("test");
        AppProperties.VersionStrategy strategy = new AppProperties.VersionStrategy();
        strategy.setType(VersionStrategyType.TOPIC_CAPTURE);
        // capture name intentionally omitted
        mapping.setVersionStrategy(strategy);
        ResolvedTopicMapping resolved = new ResolvedTopicMapping(mapping, Map.of(), "watch/test");

        assertThatThrownBy(() -> resolver.resolve(resolved))
            .isInstanceOf(SchemaResolutionException.class)
            .hasMessageContaining("capture name");
    }

    @Test
    void throwsWhenTopicCaptureValueIsMissing() {
        AppProperties.Mapping mapping = new AppProperties.Mapping();
        mapping.setId("test");
        AppProperties.VersionStrategy strategy = new AppProperties.VersionStrategy();
        strategy.setType(VersionStrategyType.TOPIC_CAPTURE);
        strategy.setCapture("version");
        mapping.setVersionStrategy(strategy);
        // captures map does not contain "version"
        ResolvedTopicMapping resolved = new ResolvedTopicMapping(mapping, Map.of(), "watch/test");

        assertThatThrownBy(() -> resolver.resolve(resolved))
            .isInstanceOf(SchemaResolutionException.class)
            .hasMessageContaining("version");
    }

    @Test
    void throwsWhenFixedVersionValueIsBlank() {
        AppProperties.Mapping mapping = new AppProperties.Mapping();
        mapping.setId("test");
        AppProperties.VersionStrategy strategy = new AppProperties.VersionStrategy();
        strategy.setType(VersionStrategyType.FIXED);
        // value intentionally omitted
        mapping.setVersionStrategy(strategy);
        ResolvedTopicMapping resolved = new ResolvedTopicMapping(mapping, Map.of(), "watch/test");

        assertThatThrownBy(() -> resolver.resolve(resolved))
            .isInstanceOf(SchemaResolutionException.class)
            .hasMessageContaining("value");
    }
}
