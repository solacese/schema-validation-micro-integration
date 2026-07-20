package com.solace.schemavalidator.registry;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.solace.schemavalidator.TestMeters;
import com.solace.schemavalidator.resolution.ArtifactType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaBundleLoaderNoEnabledVersionTest {

    @Test
    void throwsWhenNoEnabledVersionExistsForLatestEnabled() {
        SchemaRegistryClient client = new SchemaRegistryClient() {
            @Override
            public SchemaVersionMetadata getVersionMetadata(String g, String a, String v) {
                return new SchemaVersionMetadata(g, a, v, ArtifactType.JSON);
            }

            @Override
            public Map<String, Object> getArtifactMetadata(String g, String a) {
                return Map.of();
            }

            @Override
            public List<SchemaVersionSummary> getArtifactVersions(String g, String a) {
                return List.of(
                    new SchemaVersionSummary(g, a, "1.0.0", ArtifactType.JSON, "DISABLED"),
                    new SchemaVersionSummary(g, a, "1.1.0", ArtifactType.JSON, "DEPRECATED"));
            }

            @Override
            public String getVersionContent(String g, String a, String v) {
                return "{}";
            }

            @Override
            public List<ArtifactReference> getVersionReferences(String g, String a, String v) {
                return List.of();
            }

            @Override
            public Map<String, Object> getSystemInfo() {
                return Map.of();
            }
        };

        SchemaBundleLoader loader = new SchemaBundleLoader(client, Caffeine.newBuilder().build(), TestMeters.metrics());

        assertThatThrownBy(() -> loader.load("watch-demo", "product-registration", "latest-enabled"))
            .isInstanceOf(SchemaRegistryException.class)
            .hasMessageContaining("latest enabled");
    }

    @Test
    void hitsBundleCacheOnSecondLatestEnabledCallForSameResolvedVersion() {
        int[] contentCalls = {0};
        SchemaRegistryClient client = new SchemaRegistryClient() {
            @Override
            public SchemaVersionMetadata getVersionMetadata(String g, String a, String v) {
                return new SchemaVersionMetadata(g, a, v, ArtifactType.JSON);
            }

            @Override
            public Map<String, Object> getArtifactMetadata(String g, String a) {
                return Map.of();
            }

            @Override
            public List<SchemaVersionSummary> getArtifactVersions(String g, String a) {
                return List.of(new SchemaVersionSummary(g, a, "1.0.0", ArtifactType.JSON, "ENABLED"));
            }

            @Override
            public String getVersionContent(String g, String a, String v) {
                contentCalls[0]++;
                return "{\"type\":\"object\"}";
            }

            @Override
            public List<ArtifactReference> getVersionReferences(String g, String a, String v) {
                return List.of();
            }

            @Override
            public Map<String, Object> getSystemInfo() {
                return Map.of();
            }
        };

        SchemaBundleLoader loader = new SchemaBundleLoader(client, Caffeine.newBuilder().build(), TestMeters.metrics());

        SchemaBundle first = loader.load("watch-demo", "artifact", "latest-enabled");
        SchemaBundle second = loader.load("watch-demo", "artifact", "latest-enabled");

        assertThat(second).isSameAs(first);
        assertThat(contentCalls[0]).isEqualTo(1);
    }
}
