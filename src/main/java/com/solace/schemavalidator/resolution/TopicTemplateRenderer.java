package com.solace.schemavalidator.resolution;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class TopicTemplateRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z][a-zA-Z0-9_]*)}");

    public String render(String template, ResolvedTopicMapping resolvedTopicMapping, String schemaVersion) {
        Map<String, String> context = new HashMap<>(resolvedTopicMapping.captures());
        context.put("originalTopic", resolvedTopicMapping.originalTopic());
        context.put("schemaVersion", schemaVersion);
        context.put("mappingId", resolvedTopicMapping.mapping().getId());
        Matcher matcher = PLACEHOLDER.matcher(template);
        StringBuilder buffer = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = context.get(key);
            if (value == null) {
                throw new SchemaResolutionException("Route template '" + template + "' references undefined variable '" + key + "'");
            }
            matcher.appendReplacement(buffer, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(buffer);
        return buffer.toString();
    }
}
