package com.solace.schemavalidator.resolution;

import com.solace.schemavalidator.config.AppProperties;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TopicTemplateRendererTest {

    private final TopicTemplateRenderer renderer = new TopicTemplateRenderer();

    private ResolvedTopicMapping mapping(String id, String originalTopic, Map<String, String> captures) {
        AppProperties.Mapping m = new AppProperties.Mapping();
        m.setId(id);
        return new ResolvedTopicMapping(m, captures, originalTopic);
    }

    @Test
    void rendersOriginalTopicUnchangedByDefault() {
        ResolvedTopicMapping mapping = mapping("m1", "acme/site1/line2/motor3", Map.of());
        assertThat(renderer.render("{originalTopic}", mapping, "1.0.0"))
            .isEqualTo("acme/site1/line2/motor3");
    }

    @Test
    void substitutesOutcomeLabelBetweenCaptureGroups() {
        // in:  acme/site1/line2/motor3/temperature4/raw/degrees  ->  out: .../valid/degrees
        ResolvedTopicMapping mapping = mapping("m1",
            "acme/site1/line2/motor3/temperature4/raw/degrees",
            Map.of("site", "site1", "line", "line2", "motor", "motor3", "sensor", "temperature4", "unit", "degrees"));
        String rendered = renderer.render(
            "acme/{site}/{line}/{motor}/{sensor}/{outcome}/{unit}", mapping, "1.0.0", "valid");
        assertThat(rendered).isEqualTo("acme/site1/line2/motor3/temperature4/valid/degrees");
    }

    @Test
    void appendsOutcomeLabelAsTrailingLevel() {
        ResolvedTopicMapping mapping = mapping("m1", "Acme/Site1/Line2/Motor3/Temperature4", Map.of());
        assertThat(renderer.render("{originalTopic}/{outcome}", mapping, "2", "Invalid"))
            .isEqualTo("Acme/Site1/Line2/Motor3/Temperature4/Invalid");
    }

    @Test
    void supportsShortLabelsAndArbitraryCasing() {
        ResolvedTopicMapping mapping = mapping("m1", "ACME/SITE1/LINE2", Map.of());
        assertThat(renderer.render("ACME/SITE1/{outcome}", mapping, "1", "E"))
            .isEqualTo("ACME/SITE1/E");
    }

    @Test
    void exposesSchemaVersionAndMappingId() {
        ResolvedTopicMapping mapping = mapping("temperature-mapping", "acme/t", Map.of());
        assertThat(renderer.render("{mappingId}/{schemaVersion}/{outcome}", mapping, "3.2.1", "valid"))
            .isEqualTo("temperature-mapping/3.2.1/valid");
    }

    @Test
    void throwsWhenTemplateReferencesUndefinedVariable() {
        ResolvedTopicMapping mapping = mapping("m1", "acme/t", Map.of());
        assertThatThrownBy(() -> renderer.render("{missing}", mapping, "1.0.0", "valid"))
            .isInstanceOf(SchemaResolutionException.class)
            .hasMessageContaining("missing");
    }

    @Test
    void outcomePlaceholderIsUndefinedWhenNoLabelProvided() {
        ResolvedTopicMapping mapping = mapping("m1", "acme/t", Map.of());
        assertThatThrownBy(() -> renderer.render("{originalTopic}/{outcome}", mapping, "1.0.0"))
            .isInstanceOf(SchemaResolutionException.class)
            .hasMessageContaining("outcome");
    }
}
