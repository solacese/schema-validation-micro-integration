package com.solace.schemavalidator.registry;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.solace.schemavalidator.TestMeters;
import com.solace.schemavalidator.resolution.ArtifactType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class SchemaBundleLoaderTest {

    @Test
    void rejectsCyclicRegistryReferencesWithoutOverflowing() {
        SchemaRegistryClient client = mock(SchemaRegistryClient.class);
        when(client.getVersionMetadata("test", "root", "1"))
            .thenReturn(new SchemaVersionMetadata("test", "root", "1", ArtifactType.JSON));
        when(client.getVersionReferences("test", "root", "1"))
            .thenReturn(List.of(new ArtifactReference("test", "root", "1", "self.json")));
        SchemaBundleLoader loader = new SchemaBundleLoader(client, Caffeine.newBuilder().build(), TestMeters.metrics());
        assertThatThrownBy(() -> loader.load("test", "root", "1"))
            .isInstanceOf(SchemaRegistryException.class).hasMessageContaining("Cyclic schema reference");
    }

    @Test
    void recursivelyLoadsReferencesAndCachesBundle() {
        SchemaRegistryClient client = new SchemaRegistryClient() {
            @Override
            public SchemaVersionMetadata getVersionMetadata(String groupId, String artifactId, String versionExpression) {
                return new SchemaVersionMetadata(groupId, artifactId, versionExpression, ArtifactType.AVRO);
            }

            @Override
            public Map<String, Object> getArtifactMetadata(String groupId, String artifactId) {
                return Map.of("version", "1.0.0", "artifactType", "AVRO");
            }

            @Override
            public String getVersionContent(String groupId, String artifactId, String versionExpression) {
                if ("child".equals(artifactId)) {
                    return "{\"type\":\"record\",\"name\":\"Child\",\"namespace\":\"com.example\",\"fields\":[]}";
                }
                return "{\"type\":\"record\",\"name\":\"Root\",\"namespace\":\"com.example\",\"fields\":[{\"name\":\"child\",\"type\":\"com.example.Child\"}]}";
            }

            @Override
            public List<ArtifactReference> getVersionReferences(String groupId, String artifactId, String versionExpression) {
                if ("root".equals(artifactId)) {
                    return List.of(new ArtifactReference(groupId, "child", "1.0.0", "com.example.Child"));
                }
                return List.of();
            }

            @Override
            public Map<String, Object> getSystemInfo() {
                return Map.of();
            }
        };

        SchemaBundleLoader loader = new SchemaBundleLoader(client, Caffeine.newBuilder().build(), TestMeters.metrics());

        SchemaBundle first = loader.load("watch-demo", "root", "1.0.0");
        SchemaBundle second = loader.load("watch-demo", "root", "1.0.0");

        assertThat(first.documents()).hasSize(2);
        assertThat(first.compilationOrder()).extracting(document -> document.coordinate().artifactId())
            .containsExactly("child", "root");
        assertThat(second).isSameAs(first);
    }

    @Test
    void resolvesLatestEnabledFromVersionList() {
        SchemaRegistryClient client = new SchemaRegistryClient() {
            @Override
            public SchemaVersionMetadata getVersionMetadata(String groupId, String artifactId, String versionExpression) {
                return new SchemaVersionMetadata(groupId, artifactId, versionExpression, ArtifactType.JSON);
            }

            @Override
            public Map<String, Object> getArtifactMetadata(String groupId, String artifactId) {
                return Map.of();
            }

            @Override
            public List<SchemaVersionSummary> getArtifactVersions(String groupId, String artifactId) {
                return List.of(
                    new SchemaVersionSummary(groupId, artifactId, "1.0.0", ArtifactType.JSON, "ENABLED"),
                    new SchemaVersionSummary(groupId, artifactId, "1.1.0", ArtifactType.JSON, "DISABLED"),
                    new SchemaVersionSummary(groupId, artifactId, "1.0.1", ArtifactType.JSON, "ENABLED"));
            }

            @Override
            public String getVersionContent(String groupId, String artifactId, String versionExpression) {
                return "{\"type\":\"object\"}";
            }

            @Override
            public List<ArtifactReference> getVersionReferences(String groupId, String artifactId, String versionExpression) {
                return List.of();
            }

            @Override
            public Map<String, Object> getSystemInfo() {
                return Map.of();
            }
        };

        SchemaBundleLoader loader = new SchemaBundleLoader(client, Caffeine.newBuilder().build(), TestMeters.metrics());

        SchemaBundle bundle = loader.load("watch-demo", "product-registration", "latest-enabled");

        assertThat(bundle.root().coordinate().version()).isEqualTo("1.0.1");
    }
}
