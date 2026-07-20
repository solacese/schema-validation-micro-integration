package com.solace.schemavalidator.validation;

import com.solace.schemavalidator.registry.SchemaBundle;
import com.solace.schemavalidator.registry.SchemaDocument;
import com.solace.schemavalidator.registry.SchemaVersionCoordinate;
import com.solace.schemavalidator.resolution.ArtifactType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class XmlSchemaValidatorFactoryTest {

    private static final String SENSOR_XSD = """
        <?xml version="1.0" encoding="UTF-8"?>
        <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema"
                   targetNamespace="http://schemas.example.com/sensor"
                   xmlns:tns="http://schemas.example.com/sensor"
                   elementFormDefault="qualified">
          <xs:element name="SensorReading">
            <xs:complexType>
              <xs:sequence>
                <xs:element name="sensorId" type="xs:string"/>
                <xs:element name="timestamp" type="xs:long"/>
                <xs:element name="value" type="xs:double"/>
              </xs:sequence>
            </xs:complexType>
          </xs:element>
        </xs:schema>
        """;

    @Test
    void validatesXmlPayload() throws Exception {
        SchemaDocument sensor = new SchemaDocument(
            new SchemaVersionCoordinate("test", "sensor-reading", "1.0.0"),
            ArtifactType.XSD,
            SENSOR_XSD,
            List.of());
        SchemaBundle bundle = new SchemaBundle(sensor, Map.of(
            sensor.coordinate(), sensor
        ), List.of(sensor));

        CompiledValidator validator = new XmlSchemaValidatorFactory().compile(bundle);

        assertThat(validator.validate(validXml().getBytes()).valid()).isTrue();
        assertThat(validator.validate(invalidXml().getBytes()).valid()).isFalse();
    }

    private String validXml() {
        return """
            <SensorReading xmlns="http://schemas.example.com/sensor">
              <sensorId>PMNO-001</sensorId>
              <timestamp>1742897730000</timestamp>
              <value>23.5</value>
            </SensorReading>
            """;
    }

    private String invalidXml() {
        return """
            <SensorReading xmlns="http://schemas.example.com/sensor">
              <sensorId>PMNO-001</sensorId>
              <timestamp>not-a-number</timestamp>
              <value>23.5</value>
            </SensorReading>
            """;
    }
}
