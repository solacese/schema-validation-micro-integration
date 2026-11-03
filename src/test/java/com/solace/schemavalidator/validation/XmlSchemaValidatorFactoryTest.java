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

    @Test
    void blocksExternalEntitiesInXmlPayloads() {
        var root = new SchemaDocument(new SchemaVersionCoordinate("test", "root", "1"), ArtifactType.XSD,
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:element name="root" type="xs:string"/>
            </xs:schema>
            """, List.of());
        var validator = new XmlSchemaValidatorFactory().compile(new SchemaBundle(root,
            Map.of(root.coordinate(), root), List.of(root)));
        assertThat(validator.validate("""
            <!DOCTYPE root [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
            <root>&secret;</root>
            """.getBytes()).valid()).isFalse();
    }

    @Test
    void resolvesSchemaIncludesFromRegistryReferences() {
        var child = new SchemaDocument(new SchemaVersionCoordinate("test", "types", "1"), ArtifactType.XSD,
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:simpleType name="Positive"><xs:restriction base="xs:integer">
                <xs:minInclusive value="1"/>
              </xs:restriction></xs:simpleType>
            </xs:schema>
            """, List.of());
        var root = new SchemaDocument(new SchemaVersionCoordinate("test", "root", "1"), ArtifactType.XSD,
            """
            <xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema">
              <xs:include schemaLocation="shared/types.xsd"/>
              <xs:element name="root" type="Positive"/>
            </xs:schema>
            """, List.of(new com.solace.schemavalidator.registry.ArtifactReference("test", "types", "1", "shared/types.xsd")));
        var validator = new XmlSchemaValidatorFactory().compile(new SchemaBundle(root,
            Map.of(root.coordinate(), root, child.coordinate(), child), List.of(child, root)));
        assertThat(validator.validate("<root>1</root>".getBytes()).valid()).isTrue();
        assertThat(validator.validate("<root>0</root>".getBytes()).valid()).isFalse();
    }

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
        assertThat(validator.validate(validXml().getBytes(java.nio.charset.StandardCharsets.UTF_16)).valid()).isTrue();
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
