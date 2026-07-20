package com.solace.schemavalidator.registry;

import java.util.List;
import java.util.Map;

public interface SchemaRegistryClient {

    SchemaVersionMetadata getVersionMetadata(String groupId, String artifactId, String versionExpression);

    Map<String, Object> getArtifactMetadata(String groupId, String artifactId);

    default List<SchemaVersionSummary> getArtifactVersions(String groupId, String artifactId) {
        throw new UnsupportedOperationException("Artifact version listing is not implemented");
    }

    String getVersionContent(String groupId, String artifactId, String versionExpression);

    List<ArtifactReference> getVersionReferences(String groupId, String artifactId, String versionExpression);

    Map<String, Object> getSystemInfo();
}
