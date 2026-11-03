package com.solace.schemavalidator;

import com.solace.schemavalidator.config.IngressSubscriptionInitializer;
import com.solace.schemavalidator.registry.SchemaRegistryClient;
import com.solace.schemavalidator.validation.ValidatorFactory;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binder.test.EnableTestBinder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.ObjectMapper;
import static org.assertj.core.api.Assertions.*;

@SpringBootTest(properties = {
    "spring.cloud.stream.default-binder=integration",
    "spring.cloud.stream.bindings.schemaValidationProcessor-in-0.binder=integration",
    "spring.cloud.stream.bindings.schemaValidationResults-out-0.binder=integration",
    "app.prewarm.enabled=false"
})
@EnableTestBinder
class ApplicationStartupTest {
    @MockitoBean IngressSubscriptionInitializer ingress;
    @MockitoBean SchemaRegistryClient registry;
    @Autowired ObjectMapper mapper;
    @Autowired List<ValidatorFactory> validators;

    @Test
    void bootsWithAllFourValidatorsAndJackson3() {
        assertThat(mapper).isNotNull();
        assertThat(validators).extracting(factory -> factory.supportedType().getRegistryType())
            .containsExactlyInAnyOrder("JSON", "AVRO", "PROTOBUF", "XML");
    }
}
