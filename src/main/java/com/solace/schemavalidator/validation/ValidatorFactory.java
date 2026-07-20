package com.solace.schemavalidator.validation;

import com.solace.schemavalidator.registry.SchemaBundle;
import com.solace.schemavalidator.resolution.ArtifactType;

public interface ValidatorFactory {

    ArtifactType supportedType();

    CompiledValidator compile(SchemaBundle schemaBundle);
}
