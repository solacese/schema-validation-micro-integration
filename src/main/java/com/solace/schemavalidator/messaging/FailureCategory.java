package com.solace.schemavalidator.messaging;

public enum FailureCategory {
    NONE,
    VALIDATION,
    MAPPING,
    REGISTRY,
    PUBLISH,
    PROCESSING
}
