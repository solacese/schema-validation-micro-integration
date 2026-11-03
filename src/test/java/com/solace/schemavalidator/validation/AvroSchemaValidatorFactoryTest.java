package com.solace.schemavalidator.validation;

import com.solace.schemavalidator.registry.SchemaBundle;
import com.solace.schemavalidator.registry.SchemaDocument;
import com.solace.schemavalidator.registry.SchemaVersionCoordinate;
import com.solace.schemavalidator.resolution.ArtifactType;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.io.EncoderFactory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AvroSchemaValidatorFactoryTest {

    private static final String SENSOR_SCHEMA = """
        {
          "type": "record",
          "name": "SensorReading",
          "namespace": "com.example.iot",
          "fields": [
            {"name": "sensorId", "type": "string"},
            {"name": "timestamp", "type": "long"},
            {"name": "value", "type": "double"}
          ]
        }
        """;

    @Test
    void validatesBinaryAvroPayload() throws Exception {
        SchemaDocument sensor = new SchemaDocument(
            new SchemaVersionCoordinate("test", "sensor-reading", "1.0.0"),
            ArtifactType.AVRO,
            SENSOR_SCHEMA,
            List.of());
        SchemaBundle bundle = new SchemaBundle(sensor, Map.of(
            sensor.coordinate(), sensor
        ), List.of(sensor));

        CompiledValidator validator = new AvroSchemaValidatorFactory().compile(bundle);

        assertThat(validator.validate(validPayload()).valid()).isTrue();
        assertThat(validator.validate(new byte[]{0x01, 0x02, 0x03}).valid()).isFalse();
        byte[] valid = validPayload();
        byte[] trailing = java.util.Arrays.copyOf(valid, valid.length + 1);
        assertThat(validator.validate(trailing).valid()).isFalse();
    }

    private byte[] validPayload() throws Exception {
        Schema schema = new Schema.Parser().parse(SENSOR_SCHEMA);
        GenericData.Record record = new GenericData.Record(schema);
        record.put("sensorId", "PMNO-001");
        record.put("timestamp", 1742897730000L);
        record.put("value", 23.5);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        GenericDatumWriter<GenericData.Record> writer = new GenericDatumWriter<>(schema);
        var encoder = EncoderFactory.get().binaryEncoder(out, null);
        writer.write(record, encoder);
        encoder.flush();
        return out.toByteArray();
    }
}
