package com.solace.schemavalidator.resolution;

import com.solace.schemavalidator.config.AppProperties;
import java.util.Map;

public record ResolvedTopicMapping(AppProperties.Mapping mapping, Map<String, String> captures, String originalTopic) {
}
