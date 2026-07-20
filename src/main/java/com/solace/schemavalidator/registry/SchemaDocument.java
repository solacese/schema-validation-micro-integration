package com.solace.schemavalidator.registry;

import com.solace.schemavalidator.resolution.ArtifactType;
import java.util.List;

public record SchemaDocument(SchemaVersionCoordinate coordinate,
                             ArtifactType artifactType,
                             String content,
                             List<ArtifactReference> references) {
}
