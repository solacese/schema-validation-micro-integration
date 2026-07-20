package com.solace.schemavalidator.resolution;

import com.github.benmanes.caffeine.cache.Cache;
import com.solace.schemavalidator.config.AppProperties;
import com.solace.schemavalidator.observability.SchemaValidationMetrics;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class TopicMappingResolver {

    private final List<CompiledTopicMapping> mappings;
    private final Cache<String, ResolvedTopicMapping> cache;
    private final SchemaValidationMetrics metrics;

    public TopicMappingResolver(AppProperties properties,
                                Cache<String, ResolvedTopicMapping> cache,
                                SchemaValidationMetrics metrics) {
        this.cache = cache;
        this.metrics = metrics;
        this.mappings = properties.getMappings().stream()
            .map(mapping -> new CompiledTopicMapping(mapping, TopicPattern.compile(mapping.getTopicPattern())))
            .toList();
    }

    public ResolvedTopicMapping resolve(String topic) {
        ResolvedTopicMapping cached = cache.getIfPresent(topic);
        if (cached != null) {
            metrics.recordTopicResolutionCacheHit();
            return cached;
        }
        metrics.recordTopicResolutionCacheMiss();
        List<ResolvedTopicMapping> matches = new ArrayList<>();
        for (CompiledTopicMapping mapping : mappings) {
            Map<String, String> captures = mapping.pattern().match(topic);
            if (captures != null) {
                matches.add(new ResolvedTopicMapping(mapping.mapping(), captures, topic));
            }
        }
        if (matches.isEmpty()) {
            throw new SchemaResolutionException("No topic mapping matched topic '" + topic + "'");
        }
        if (matches.size() > 1) {
            throw new SchemaResolutionException("Ambiguous topic mapping for topic '" + topic + "'");
        }
        ResolvedTopicMapping resolved = matches.get(0);
        cache.put(topic, resolved);
        return resolved;
    }

    private record CompiledTopicMapping(AppProperties.Mapping mapping, TopicPattern pattern) {
    }
}
