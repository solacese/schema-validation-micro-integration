package com.solace.schemavalidator;

import com.solace.schemavalidator.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties(AppProperties.class)
public class SchemaValidationMicroIntegrationApplication {

    public static void main(String[] args) {
        SpringApplication.run(SchemaValidationMicroIntegrationApplication.class, args);
    }
}
