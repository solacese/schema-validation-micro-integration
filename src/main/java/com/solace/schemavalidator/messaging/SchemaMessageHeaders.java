package com.solace.schemavalidator.messaging;

public final class SchemaMessageHeaders {

    public static final String VALIDATION_STATUS = "mi_validation_status";
    public static final String SCHEMA_GROUP = "mi_schema_group";
    public static final String SCHEMA_ARTIFACT = "mi_schema_artifact";
    public static final String SCHEMA_VERSION = "mi_schema_version";
    public static final String SCHEMA_TYPE = "mi_schema_type";
    public static final String VALIDATION_TIMESTAMP = "mi_validation_timestamp";
    public static final String FAILURE_CATEGORY = "mi_failure_category";
    public static final String FAILURE_REASON = "mi_failure_reason";
    public static final String ORIGINAL_TOPIC = "mi_original_topic";
    public static final String MAPPING_ID = "mi_mapping_id";
    public static final String EQUIPMENT_KEY = "mi_equipment_key";

    private SchemaMessageHeaders() {
    }
}
