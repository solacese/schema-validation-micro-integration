package com.solace.schemavalidator.validation;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import com.networknt.schema.Error;
import com.solace.schemavalidator.registry.SchemaBundle;
import com.solace.schemavalidator.registry.SchemaDocument;
import com.solace.schemavalidator.registry.ArtifactReference;
import com.solace.schemavalidator.resolution.ArtifactType;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

@Component
public class JsonSchemaValidatorFactory implements ValidatorFactory {

    private final ObjectMapper objectMapper;

    public JsonSchemaValidatorFactory(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    @Override
    public ArtifactType supportedType() {
        return ArtifactType.JSON;
    }

    @Override
    public CompiledValidator compile(SchemaBundle schemaBundle) {
        Map<String, String> schemas = new LinkedHashMap<>();
        for (SchemaDocument document : schemaBundle.compilationOrder()) {
            try {
                JsonNode node = objectMapper.readTree(document.content());
                JsonNode idNode = node.get("$id");
                if (idNode != null && idNode.isTextual()) {
                    schemas.put(idNode.asText(), document.content());
                }
                schemas.put(document.coordinate().artifactId(), document.content());
                for (ArtifactReference reference : document.references()) {
                    SchemaDocument referenced = schemaBundle.documents().get(
                        new com.solace.schemavalidator.registry.SchemaVersionCoordinate(
                            reference.groupId(), reference.artifactId(), reference.version()));
                    if (referenced != null) {
                        schemas.put(reference.name(), referenced.content());
                        int fragmentIndex = reference.name().indexOf('#');
                        if (fragmentIndex > 0) {
                            schemas.put(reference.name().substring(0, fragmentIndex), referenced.content());
                        }
                    }
                }
            } catch (Exception ex) {
                throw new IllegalStateException("Failed to parse JSON schema for " + document.coordinate(), ex);
            }
        }
        SchemaRegistry factory = SchemaRegistry.withDefaultDialect(
            SpecificationVersion.DRAFT_2020_12,
            builder -> builder.schemas(schemas).schemaLoader(loader -> loader.fetchRemoteResources(false)));
        Schema jsonSchema = factory.getSchema(schemaBundle.root().content(), InputFormat.JSON);
        return payload -> {
            try {
                JsonNode payloadNode = objectMapper.readTree(payload);
                List<Error> messages = jsonSchema.validate(payloadNode);
                if (messages.isEmpty()) {
                    return ValidationOutcome.success();
                }
                return ValidationOutcome.failure(messages.stream()
                    .map(Error::getMessage)
                    .collect(Collectors.joining("; ")));
            } catch (Exception ex) {
                return ValidationOutcome.failure("JSON payload could not be validated: " +
                    ex.getMessage());
            }
        };
    }
}
