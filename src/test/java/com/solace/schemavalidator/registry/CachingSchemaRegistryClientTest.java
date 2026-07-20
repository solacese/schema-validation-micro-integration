package com.solace.schemavalidator.registry;

import com.github.benmanes.caffeine.cache.Caffeine;
import com.solace.schemavalidator.resolution.ArtifactType;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CachingSchemaRegistryClientTest {

    @Test
    void cachesRepeatedRegistryLookups() {
        AtomicInteger metadataCalls = new AtomicInteger();
        AtomicInteger versionsCalls = new AtomicInteger();
        AtomicInteger contentCalls = new AtomicInteger();
        AtomicInteger referencesCalls = new AtomicInteger();

        SchemaRegistryClient delegate = new SchemaRegistryClient() {
            @Override
            public SchemaVersionMetadata getVersionMetadata(String groupId, String artifactId, String versionExpression) {
                metadataCalls.incrementAndGet();
                return new SchemaVersionMetadata(groupId, artifactId, versionExpression, ArtifactType.JSON);
            }

            @Override
            public Map<String, Object> getArtifactMetadata(String groupId, String artifactId) {
                return Map.of("artifactId", artifactId);
            }

            @Override
            public List<SchemaVersionSummary> getArtifactVersions(String groupId, String artifactId) {
                versionsCalls.incrementAndGet();
                return List.of(new SchemaVersionSummary(groupId, artifactId, "1.0.0", ArtifactType.JSON, "ENABLED"));
            }

            @Override
            public String getVersionContent(String groupId, String artifactId, String versionExpression) {
                contentCalls.incrementAndGet();
                return "{\"type\":\"object\"}";
            }

            @Override
            public List<ArtifactReference> getVersionReferences(String groupId, String artifactId, String versionExpression) {
                referencesCalls.incrementAndGet();
                return List.of(new ArtifactReference(groupId, "address", "1.0.0", "common/address.schema.json"));
            }

            @Override
            public Map<String, Object> getSystemInfo() {
                return Map.of();
            }
        };

        CachingSchemaRegistryClient client = new CachingSchemaRegistryClient(
            delegate,
            Caffeine.newBuilder().build(),
            Caffeine.newBuilder().build(),
            Caffeine.newBuilder().build(),
            Caffeine.newBuilder().build(),
            Caffeine.newBuilder().build());

        assertThat(client.getVersionMetadata("watch-demo", "product-registration", "1.0.0"))
            .isEqualTo(client.getVersionMetadata("watch-demo", "product-registration", "1.0.0"));
        assertThat(client.getArtifactVersions("watch-demo", "product-registration"))
            .containsExactly(new SchemaVersionSummary("watch-demo", "product-registration", "1.0.0", ArtifactType.JSON, "ENABLED"));
        assertThat(client.getArtifactVersions("watch-demo", "product-registration"))
            .hasSize(1);
        assertThat(client.getVersionContent("watch-demo", "product-registration", "1.0.0"))
            .isEqualTo(client.getVersionContent("watch-demo", "product-registration", "1.0.0"));
        assertThat(client.getVersionReferences("watch-demo", "product-registration", "1.0.0"))
            .containsExactly(new ArtifactReference("watch-demo", "address", "1.0.0", "common/address.schema.json"));
        assertThat(client.getVersionReferences("watch-demo", "product-registration", "1.0.0"))
            .hasSize(1);

        assertThat(metadataCalls).hasValue(1);
        assertThat(versionsCalls).hasValue(1);
        assertThat(contentCalls).hasValue(1);
        assertThat(referencesCalls).hasValue(1);
    }
}
