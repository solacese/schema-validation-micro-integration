package com.solace.schemavalidator.resolution;

public enum ArtifactType {
    AVRO("AVRO", "application/json"),
    JSON("JSON", "application/json"),
    XSD("XML", "application/xml"),
    PROTOBUF("PROTOBUF", "application/x-protobuf"),
    UNKNOWN("UNKNOWN", "application/octet-stream");

    private final String registryType;
    private final String contentType;

    ArtifactType(String registryType, String contentType) {
        this.registryType = registryType;
        this.contentType = contentType;
    }

    public String getRegistryType() {
        return registryType;
    }

    public String getContentType() {
        return contentType;
    }

    public static ArtifactType fromRegistryType(String value) {
        if (value == null) {
            return UNKNOWN;
        }
        for (ArtifactType type : values()) {
            if (type == UNKNOWN) {
                continue;
            }
            if (type.registryType.equalsIgnoreCase(value)) {
                return type;
            }
            if (type == XSD && "XSD".equalsIgnoreCase(value)) {
                return type;
            }
        }
        return UNKNOWN;
    }
}
