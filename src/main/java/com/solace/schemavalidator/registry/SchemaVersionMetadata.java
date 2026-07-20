package com.solace.schemavalidator.registry;

import com.solace.schemavalidator.resolution.ArtifactType;

public record SchemaVersionMetadata(String groupId,
                                    String artifactId,
                                    String version,
                                    ArtifactType artifactType) {
}
