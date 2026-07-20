package com.solace.schemavalidator.resolution;

import com.solace.schemavalidator.config.AppProperties;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class VersionExpressionResolver {

    public String resolve(ResolvedTopicMapping resolvedTopicMapping) {
        AppProperties.VersionStrategy strategy = resolvedTopicMapping.mapping().getVersionStrategy();
        return switch (strategy.getType()) {
            case TOPIC_CAPTURE -> requireCapture(strategy, resolvedTopicMapping);
            case FIXED -> requireValue(strategy, resolvedTopicMapping);
            case BRANCH -> "branch=" + resolveBranch(strategy, resolvedTopicMapping);
            case LATEST_ENABLED -> "latest-enabled";
        };
    }

    private String resolveBranch(AppProperties.VersionStrategy strategy, ResolvedTopicMapping resolvedTopicMapping) {
        if (StringUtils.hasText(strategy.getValue())) {
            return strategy.getValue();
        }
        return requireCapture(strategy, resolvedTopicMapping);
    }

    private String requireCapture(AppProperties.VersionStrategy strategy, ResolvedTopicMapping resolvedTopicMapping) {
        String captureName = strategy.getCapture();
        if (!StringUtils.hasText(captureName)) {
            throw new SchemaResolutionException("Version strategy requires a capture name for mapping '" +
                resolvedTopicMapping.mapping().getId() + "'");
        }
        String value = resolvedTopicMapping.captures().get(captureName);
        if (!StringUtils.hasText(value)) {
            throw new SchemaResolutionException("Topic capture '" + captureName + "' was not resolved for mapping '" +
                resolvedTopicMapping.mapping().getId() + "'");
        }
        return value;
    }

    private String requireValue(AppProperties.VersionStrategy strategy, ResolvedTopicMapping resolvedTopicMapping) {
        if (!StringUtils.hasText(strategy.getValue())) {
            throw new SchemaResolutionException("Fixed version strategy requires a value for mapping '" +
                resolvedTopicMapping.mapping().getId() + "'");
        }
        return strategy.getValue();
    }
}
