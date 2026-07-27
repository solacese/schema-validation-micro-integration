package com.solace.schemavalidator.resolution;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class TopicTemplateRenderer {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{([a-zA-Z][a-zA-Z0-9_]*)}");

    /**
     * Renders a routing template without a validation-outcome label. Templates that reference
     * {@code {outcome}} will fail with a {@link SchemaResolutionException} when rendered through
     * this overload; use {@link #render(String, ResolvedTopicMapping, String, String)} instead.
     */
    public String render(String template, ResolvedTopicMapping resolvedTopicMapping, String schemaVersion) {
        return render(template, resolvedTopicMapping, schemaVersion, null);
    }

    /**
     * Renders a routing template, substituting supported placeholders. Supported variables:
     * <ul>
     *   <li>{@code {originalTopic}} &ndash; the consumed topic verbatim</li>
     *   <li>{@code {schemaVersion}} &ndash; the resolved schema version</li>
     *   <li>{@code {mappingId}} &ndash; the id of the resolved topic mapping</li>
     *   <li>{@code {outcome}} &ndash; the configurable validation-outcome label (e.g. valid / invalid / error)</li>
     *   <li>any named topic capture group from the matched topic pattern</li>
     * </ul>
     *
     * @param outcomeLabel the configured label for the current validation outcome, or {@code null}
     *                     when no outcome is applicable (in which case {@code {outcome}} is undefined)
     */
    public String render(String template, ResolvedTopicMapping resolvedTopicMapping, String schemaVersion, String outcomeLabel) {
        Map<String, String> context = new HashMap<>(resolvedTopicMapping.captures());
        context.put("originalTopic", resolvedTopicMapping.originalTopic());
        context.put("schemaVersion", schemaVersion);
        context.put("mappingId", resolvedTopicMapping.mapping().getId());
        if (outcomeLabel != null) {
            context.put("outcome", outcomeLabel);
        }
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
