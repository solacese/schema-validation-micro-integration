package com.solace.schemavalidator.validation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.solace.schemavalidator.registry.ArtifactReference;
import com.solace.schemavalidator.registry.SchemaBundle;
import com.solace.schemavalidator.registry.SchemaDocument;
import com.solace.schemavalidator.registry.SchemaVersionCoordinate;
import com.solace.schemavalidator.resolution.ArtifactType;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JsonSchemaValidatorFactoryTest {

    @Test
    void validatesJsonPayloadAgainstReferencedSchema() throws Exception {
        SchemaDocument metadata = new SchemaDocument(
            new SchemaVersionCoordinate("manufacturing-iot", "metadata", "1.0.0"),
            ArtifactType.JSON,
            Files.readString(Path.of("schemas/json/mqtt-envelope/metadata.v1.schema.json")),
            List.of());
        SchemaDocument context = new SchemaDocument(
            new SchemaVersionCoordinate("manufacturing-iot", "context-generic", "1.0.0"),
            ArtifactType.JSON,
            Files.readString(Path.of("schemas/json/mqtt-envelope/context.generic.v1.schema.json")),
            List.of());
        SchemaDocument pubFiles = new SchemaDocument(
            new SchemaVersionCoordinate("manufacturing-iot", "pub-files", "1.0.0"),
            ArtifactType.JSON,
            Files.readString(Path.of("schemas/json/mqtt-envelope/pub-files.v1.schema.json")),
            List.of(
                new ArtifactReference("manufacturing-iot", "metadata", "1.0.0",
                    "https://example.com/schemas/mqtt-envelope/metadata.v1.schema.json"),
                new ArtifactReference("manufacturing-iot", "context-generic", "1.0.0",
                    "https://example.com/schemas/mqtt-envelope/context.generic.v1.schema.json")));
        SchemaBundle bundle = new SchemaBundle(pubFiles, Map.of(
            metadata.coordinate(), metadata,
            context.coordinate(), context,
            pubFiles.coordinate(), pubFiles
        ), List.of(metadata, context, pubFiles));

        CompiledValidator validator = new JsonSchemaValidatorFactory(new ObjectMapper()).compile(bundle);

        assertThat(validator.validate(validPayload()).valid()).isTrue();
        assertThat(validator.validate(invalidPayload()).valid()).isFalse();
    }

    private byte[] validPayload() {
        return """
            {
              "metadata": {
                "topic": "Acme/SA/PMNO-001/DATASCIENCE/JSON-v1/FromEqt/PUB_FILES",
                "timestamp": 1742897730000,
                "message_id": "550e8400-e29b-41d4-a716-446655440000",
                "seq": 42
              },
              "data": {
                "files": [
                  {
                    "filename": "assembly-2026-03.csv",
                    "path": "/export/production/assembly",
                    "document_type": "Tableau",
                    "byte_size": 2048
                  }
                ]
              }
            }
            """.getBytes();
    }

    private byte[] invalidPayload() {
        return """
            {
              "metadata": {
                "topic": "Acme/SA/PMNO-001/DATASCIENCE/JSON-v1/FromEqt/PUB_FILES",
                "timestamp": 1742897730000,
                "seq": 42
              },
              "data": {
                "files": [
                  {
                    "filename": "assembly-2026-03.csv",
                    "path": "/export/production/assembly",
                    "document_type": "Tableau",
                    "byte_size": 2048
                  }
                ]
              }
            }
            """.getBytes();
    }
}
