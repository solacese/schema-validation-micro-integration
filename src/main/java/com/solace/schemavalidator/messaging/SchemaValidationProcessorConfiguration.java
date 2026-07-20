package com.solace.schemavalidator.messaging;

import java.util.function.Consumer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.Message;

@Configuration
public class SchemaValidationProcessorConfiguration {

    @Bean
    public Consumer<Message<?>> schemaValidationProcessor(SchemaValidationService schemaValidationService) {
        return schemaValidationService::handle;
    }
}
