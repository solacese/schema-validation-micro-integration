package com.solace.schemavalidator.registry;

import com.github.benmanes.caffeine.cache.Cache;
import com.solace.schemavalidator.observability.SchemaValidationMetrics;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class SchemaBundleLoader {

    private final SchemaRegistryClient schemaRegistryClient;
    private final Cache<SchemaVersionCoordinate, SchemaBundle> cache;
    private final SchemaValidationMetrics metrics;

    public SchemaBundleLoader(SchemaRegistryClient schemaRegistryClient,
                              Cache<SchemaVersionCoordinate, SchemaBundle> cache,
                              SchemaValidationMetrics metrics) {
        this.schemaRegistryClient = schemaRegistryClient;
        this.cache = cache;
        this.metrics = metrics;
    }

    public SchemaBundle load(String groupId, String artifactId, String versionExpression) {
        SchemaVersionMetadata metadata;
        if ("latest-enabled".equals(versionExpression)) {
            // Resolve the concrete version first; then fall through to the coordinate-keyed cache.
            // This prevents every "latest-enabled" call from re-walking the version list even when
            // the resolved bundle is already cached.
            SchemaVersionSummary latestEnabled = null;
            for (SchemaVersionSummary version : schemaRegistryClient.getArtifactVersions(groupId, artifactId)) {
                if ("ENABLED".equalsIgnoreCase(version.state())) {
                    latestEnabled = version;
                }
            }
            if (latestEnabled == null) {
                throw new SchemaRegistryException("Artifact metadata did not expose a resolved latest enabled version for "
                    + groupId + "/" + artifactId);
            }
            // Check coordinate cache before allocating the full bundle
            SchemaVersionCoordinate resolvedCoordinate = new SchemaVersionCoordinate(
                latestEnabled.groupId(), latestEnabled.artifactId(), latestEnabled.version());
            SchemaBundle cached = cache.getIfPresent(resolvedCoordinate);
            if (cached != null) {
                metrics.recordSchemaBundleCacheHit();
                return cached;
            }
            metadata = new SchemaVersionMetadata(
                latestEnabled.groupId(),
                latestEnabled.artifactId(),
                latestEnabled.version(),
                latestEnabled.artifactType());
        } else {
            metadata = schemaRegistryClient.getVersionMetadata(groupId, artifactId, versionExpression);
        }
        SchemaVersionCoordinate coordinate = new SchemaVersionCoordinate(metadata.groupId(), metadata.artifactId(), metadata.version());
        SchemaBundle cached = cache.getIfPresent(coordinate);
        if (cached != null) {
            metrics.recordSchemaBundleCacheHit();
            return cached;
        }
        metrics.recordSchemaBundleCacheMiss();
        Map<SchemaVersionCoordinate, SchemaDocument> documents = new LinkedHashMap<>();
        List<SchemaDocument> compilationOrder = new ArrayList<>();
        SchemaDocument root = loadRecursive(metadata, documents, compilationOrder);
        SchemaBundle bundle = new SchemaBundle(root, documents, List.copyOf(compilationOrder));
        cache.put(coordinate, bundle);
        return bundle;
    }

    private SchemaDocument loadRecursive(SchemaVersionMetadata metadata,
                                         Map<SchemaVersionCoordinate, SchemaDocument> documents,
                                         List<SchemaDocument> compilationOrder) {
        SchemaVersionCoordinate coordinate = new SchemaVersionCoordinate(metadata.groupId(), metadata.artifactId(), metadata.version());
        SchemaDocument existing = documents.get(coordinate);
        if (existing != null) {
            return existing;
        }
        List<ArtifactReference> references = schemaRegistryClient.getVersionReferences(
            metadata.groupId(), metadata.artifactId(), metadata.version());
        for (ArtifactReference reference : references) {
            SchemaVersionMetadata childMetadata = schemaRegistryClient.getVersionMetadata(
                reference.groupId(), reference.artifactId(), reference.version());
            loadRecursive(childMetadata, documents, compilationOrder);
        }
        SchemaDocument document = new SchemaDocument(
            coordinate,
            metadata.artifactType(),
            schemaRegistryClient.getVersionContent(metadata.groupId(), metadata.artifactId(), metadata.version()),
            List.copyOf(references));
        documents.put(coordinate, document);
        compilationOrder.add(document);
        return document;
    }
}
