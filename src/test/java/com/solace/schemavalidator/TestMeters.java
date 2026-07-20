package com.solace.schemavalidator;

import com.solace.schemavalidator.observability.SchemaValidationMetrics;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

public final class TestMeters {

    private TestMeters() {
    }

    public static SchemaValidationMetrics metrics() {
        return new SchemaValidationMetrics(new SimpleMeterRegistry());
    }
}
