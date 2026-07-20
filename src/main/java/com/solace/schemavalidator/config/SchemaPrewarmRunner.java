package com.solace.schemavalidator.config;

import com.solace.schemavalidator.registry.SchemaBundle;
import com.solace.schemavalidator.registry.SchemaBundleLoader;
import com.solace.schemavalidator.resolution.VersionStrategyType;
import com.solace.schemavalidator.validation.ValidatorService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

@Component
public class SchemaPrewarmRunner {

    private static final Logger LOGGER = LoggerFactory.getLogger(SchemaPrewarmRunner.class);

    private final AppProperties properties;
    private final SchemaBundleLoader schemaBundleLoader;
    private final ValidatorService validatorService;

    public SchemaPrewarmRunner(AppProperties properties,
                               SchemaBundleLoader schemaBundleLoader,
                               ValidatorService validatorService) {
        this.properties = properties;
        this.schemaBundleLoader = schemaBundleLoader;
        this.validatorService = validatorService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void prewarm() {
        if (!properties.getPrewarm().isEnabled()) {
            return;
        }
        for (AppProperties.Mapping mapping : properties.getMappings()) {
            String versionExpression = prewarmableVersionExpression(mapping);
            if (versionExpression == null) {
                LOGGER.info("event=prewarm_skipped mappingId={} reason=dynamic_version_resolution", mapping.getId());
                continue;
            }
            try {
                SchemaBundle bundle = schemaBundleLoader.load(mapping.getSchemaGroup(), mapping.getArtifactId(), versionExpression);
                validatorService.getValidator(bundle);
                LOGGER.info("event=prewarm_completed mappingId={} versionExpression={}", mapping.getId(), versionExpression);
            } catch (RuntimeException ex) {
                LOGGER.warn("event=prewarm_failed mappingId={} reason={}", mapping.getId(), ex.getMessage());
            }
        }
    }

    private String prewarmableVersionExpression(AppProperties.Mapping mapping) {
        AppProperties.VersionStrategy strategy = mapping.getVersionStrategy();
        return switch (strategy.getType()) {
            case FIXED -> strategy.getValue();
            case LATEST_ENABLED -> "latest-enabled";
            case BRANCH -> StringUtils.hasText(strategy.getValue()) ? "branch=" + strategy.getValue() : null;
            case TOPIC_CAPTURE -> null;
        };
    }
}
