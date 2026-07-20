package com.solace.schemavalidator.observability;

import com.solace.schemavalidator.registry.SchemaRegistryClient;
import com.solace.schemavalidator.registry.SchemaRegistryException;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class SchemaRegistryHealthIndicator implements HealthIndicator {

    private final SchemaRegistryClient schemaRegistryClient;

    public SchemaRegistryHealthIndicator(SchemaRegistryClient schemaRegistryClient) {
        this.schemaRegistryClient = schemaRegistryClient;
    }

    @Override
    public Health health() {
        try {
            return Health.up().withDetails(schemaRegistryClient.getSystemInfo()).build();
        } catch (SchemaRegistryException ex) {
            return Health.down(ex).build();
        }
    }
}
