package com.solace.schemavalidator.validation;

import com.solace.schemavalidator.registry.SchemaBundle;
import com.solace.schemavalidator.registry.SchemaDocument;
import com.solace.schemavalidator.resolution.ArtifactType;
import java.io.ByteArrayInputStream;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericDatumReader;
import org.apache.avro.io.DecoderFactory;
import org.springframework.stereotype.Component;

@Component
public class AvroSchemaValidatorFactory implements ValidatorFactory {

    @Override
    public ArtifactType supportedType() {
        return ArtifactType.AVRO;
    }

    @Override
    public CompiledValidator compile(SchemaBundle schemaBundle) {
        Schema.Parser parser = new Schema.Parser();
        Map<String, Schema> parsed = new LinkedHashMap<>();
        for (SchemaDocument document : schemaBundle.compilationOrder()) {
            Schema schema = parser.parse(document.content());
            parsed.put(document.coordinate().artifactId(), schema);
        }
        Schema rootSchema = parsed.get(schemaBundle.root().coordinate().artifactId());
        if (rootSchema == null) {
            throw new IllegalStateException("Missing parsed root Avro schema");
        }
        GenericDatumReader<Object> reader = new GenericDatumReader<>(rootSchema);
        return payload -> {
            try {
                reader.read(null, DecoderFactory.get().binaryDecoder(new ByteArrayInputStream(payload), null));
                return ValidationOutcome.success();
            } catch (Exception ex) {
                return ValidationOutcome.failure(ex.getMessage());
            }
        };
    }
}
