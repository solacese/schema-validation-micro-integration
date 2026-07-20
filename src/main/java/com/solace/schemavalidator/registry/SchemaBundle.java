package com.solace.schemavalidator.registry;

import java.util.List;
import java.util.Map;

public record SchemaBundle(SchemaDocument root,
                           Map<SchemaVersionCoordinate, SchemaDocument> documents,
                           List<SchemaDocument> compilationOrder) {
}
