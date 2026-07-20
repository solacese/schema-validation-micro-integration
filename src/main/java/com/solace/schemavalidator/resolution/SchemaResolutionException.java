package com.solace.schemavalidator.resolution;

public class SchemaResolutionException extends RuntimeException {

    public SchemaResolutionException(String message) {
        super(message);
    }

    public SchemaResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}
