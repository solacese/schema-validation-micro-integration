package com.solace.schemavalidator.resolution;

import com.solace.schemavalidator.config.AppProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class VersionExpressionResolverTest {

    private final VersionExpressionResolver resolver = new VersionExpressionResolver();

    @Test
    void resolvesTopicCapture() {
        assertThat(resolver.resolve(mapping(VersionStrategyType.TOPIC_CAPTURE, "version", null, Map.of("version", "1.0.0"))))
            .isEqualTo("1.0.0");
    }

    @Test
    void resolvesFixedVersion() {
        assertThat(resolver.resolve(mapping(VersionStrategyType.FIXED, null, "1.2.3", Map.of())))
            .isEqualTo("1.2.3");
    }

    @Test
    void resolvesBranchFromCapture() {
        assertThat(resolver.resolve(mapping(VersionStrategyType.BRANCH, "branch", null, Map.of("branch", "gold"))))
            .isEqualTo("branch=gold");
    }

    @Test
    void resolvesLatestEnabledMarker() {
        assertThat(resolver.resolve(mapping(VersionStrategyType.LATEST_ENABLED, null, null, Map.of())))
            .isEqualTo("latest-enabled");
    }

    private ResolvedTopicMapping mapping(VersionStrategyType type, String capture, String value, Map<String, String> captures) {
        AppProperties.Mapping mapping = new AppProperties.Mapping();
        mapping.setId("test");
        AppProperties.VersionStrategy strategy = new AppProperties.VersionStrategy();
        strategy.setType(type);
        strategy.setCapture(capture);
        strategy.setValue(value);
        mapping.setVersionStrategy(strategy);
        return new ResolvedTopicMapping(mapping, captures, "watch/test");
    }
}
