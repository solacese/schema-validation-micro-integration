package com.solace.schemavalidator.validation;

import com.solace.schemavalidator.registry.ArtifactReference;
import com.solace.schemavalidator.registry.SchemaBundle;
import com.solace.schemavalidator.registry.SchemaDocument;
import com.solace.schemavalidator.resolution.ArtifactType;
import java.io.StringReader;
import java.util.HashMap;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.Source;
import javax.xml.transform.stream.StreamSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import javax.xml.validation.Validator;
import org.springframework.stereotype.Component;
import org.w3c.dom.ls.LSInput;
import org.w3c.dom.ls.LSResourceResolver;
import org.xml.sax.SAXException;

@Component
public class XmlSchemaValidatorFactory implements ValidatorFactory {

    @Override
    public ArtifactType supportedType() {
        return ArtifactType.XSD;
    }

    @Override
    public CompiledValidator compile(SchemaBundle schemaBundle) {
        Map<String, String> referenceContents = new HashMap<>();
        for (SchemaDocument document : schemaBundle.compilationOrder()) {
            referenceContents.put(document.coordinate().artifactId(), document.content());
            for (ArtifactReference reference : document.references()) {
                SchemaDocument refDocument = schemaBundle.documents().get(
                    new com.solace.schemavalidator.registry.SchemaVersionCoordinate(
                        reference.groupId(), reference.artifactId(), reference.version()));
                if (refDocument != null) {
                    referenceContents.put(reference.name(), refDocument.content());
                    referenceContents.put(reference.artifactId(), refDocument.content());
                }
            }
        }
        try {
            SchemaFactory schemaFactory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            schemaFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            schemaFactory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            schemaFactory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            schemaFactory.setResourceResolver(new InMemoryXsdResolver(referenceContents));
            Source source = new StreamSource(new StringReader(schemaBundle.root().content()));
            source.setSystemId(schemaBundle.root().coordinate().artifactId());
            Schema schema = schemaFactory.newSchema(source);
            return payload -> {
                try {
                    Validator validator = schema.newValidator();
                    validator.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
                    validator.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
                    StreamSource xml = new StreamSource(new java.io.ByteArrayInputStream(payload));
                    validator.validate(xml);
                    return ValidationOutcome.success();
                } catch (Exception ex) {
                    return ValidationOutcome.failure(ex.getMessage());
                }
            };
        } catch (SAXException ex) {
            throw new IllegalStateException("Failed to compile XSD validator", ex);
        }
    }

    private static final class InMemoryXsdResolver implements LSResourceResolver {
        private final Map<String, String> schemas;

        private InMemoryXsdResolver(Map<String, String> schemas) {
            this.schemas = schemas;
        }

        @Override
        public LSInput resolveResource(String type, String namespaceURI, String publicId, String systemId, String baseURI) {
            String content = schemas.get(systemId);
            if (content == null && systemId != null && systemId.contains("/")) {
                content = schemas.get(systemId.substring(systemId.lastIndexOf('/') + 1));
            }
            if (content == null) {
                return null;
            }
            return new StringLsInput(publicId, systemId, content);
        }
    }

    private static final class StringLsInput implements LSInput {
        private final String publicId;
        private final String systemId;
        private final String data;

        private StringLsInput(String publicId, String systemId, String data) {
            this.publicId = publicId;
            this.systemId = systemId;
            this.data = data;
        }

        @Override
        public java.io.Reader getCharacterStream() {
            return new StringReader(data);
        }

        @Override
        public void setCharacterStream(java.io.Reader characterStream) {
        }

        @Override
        public java.io.InputStream getByteStream() {
            return null;
        }

        @Override
        public void setByteStream(java.io.InputStream byteStream) {
        }

        @Override
        public String getStringData() {
            return data;
        }

        @Override
        public void setStringData(String stringData) {
        }

        @Override
        public String getSystemId() {
            return systemId;
        }

        @Override
        public void setSystemId(String systemId) {
        }

        @Override
        public String getPublicId() {
            return publicId;
        }

        @Override
        public void setPublicId(String publicId) {
        }

        @Override
        public String getBaseURI() {
            return null;
        }

        @Override
        public void setBaseURI(String baseURI) {
        }

        @Override
        public String getEncoding() {
            return "UTF-8";
        }

        @Override
        public void setEncoding(String encoding) {
        }

        @Override
        public boolean getCertifiedText() {
            return false;
        }

        @Override
        public void setCertifiedText(boolean certifiedText) {
        }
    }
}
