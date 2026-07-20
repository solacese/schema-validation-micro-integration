package com.solace.schemavalidator.registry;

import com.github.benmanes.caffeine.cache.Cache;
import java.util.List;
import java.util.Map;

public class CachingSchemaRegistryClient implements SchemaRegistryClient {

    private final SchemaRegistryClient delegate;
    private final Cache<String, SchemaVersionMetadata> versionMetadataCache;
    private final Cache<String, Map<String, Object>> artifactMetadataCache;
    private final Cache<String, List<SchemaVersionSummary>> artifactVersionsCache;
    private final Cache<String, String> versionContentCache;
    private final Cache<String, List<ArtifactReference>> versionReferencesCache;

    public CachingSchemaRegistryClient(SchemaRegistryClient delegate,
                                       Cache<String, SchemaVersionMetadata> versionMetadataCache,
                                       Cache<String, Map<String, Object>> artifactMetadataCache,
                                       Cache<String, List<SchemaVersionSummary>> artifactVersionsCache,
                                       Cache<String, String> versionContentCache,
                                       Cache<String, List<ArtifactReference>> versionReferencesCache) {
        this.delegate = delegate;
        this.versionMetadataCache = versionMetadataCache;
        this.artifactMetadataCache = artifactMetadataCache;
        this.artifactVersionsCache = artifactVersionsCache;
        this.versionContentCache = versionContentCache;
        this.versionReferencesCache = versionReferencesCache;
    }

    @Override
    public SchemaVersionMetadata getVersionMetadata(String groupId, String artifactId, String versionExpression) {
        String key = versionKey(groupId, artifactId, versionExpression);
        return versionMetadataCache.get(key, ignored -> delegate.getVersionMetadata(groupId, artifactId, versionExpression));
    }

    @Override
    public Map<String, Object> getArtifactMetadata(String groupId, String artifactId) {
        String key = artifactKey(groupId, artifactId);
        return artifactMetadataCache.get(key, ignored -> Map.copyOf(delegate.getArtifactMetadata(groupId, artifactId)));
    }

    @Override
    public List<SchemaVersionSummary> getArtifactVersions(String groupId, String artifactId) {
        String key = artifactKey(groupId, artifactId);
        return artifactVersionsCache.get(key, ignored -> List.copyOf(delegate.getArtifactVersions(groupId, artifactId)));
    }

    @Override
    public String getVersionContent(String groupId, String artifactId, String versionExpression) {
        String key = versionKey(groupId, artifactId, versionExpression);
        return versionContentCache.get(key, ignored -> delegate.getVersionContent(groupId, artifactId, versionExpression));
    }

    @Override
    public List<ArtifactReference> getVersionReferences(String groupId, String artifactId, String versionExpression) {
        String key = versionKey(groupId, artifactId, versionExpression);
        return versionReferencesCache.get(key, ignored -> List.copyOf(delegate.getVersionReferences(groupId, artifactId, versionExpression)));
    }

    @Override
    public Map<String, Object> getSystemInfo() {
        return delegate.getSystemInfo();
    }

    private String artifactKey(String groupId, String artifactId) {
        return groupId + "|" + artifactId;
    }

    private String versionKey(String groupId, String artifactId, String versionExpression) {
        return artifactKey(groupId, artifactId) + "|" + versionExpression;
    }
}
