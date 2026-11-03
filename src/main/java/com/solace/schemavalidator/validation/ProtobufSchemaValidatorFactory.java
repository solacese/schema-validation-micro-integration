package com.solace.schemavalidator.validation;

import com.solace.schemavalidator.registry.SchemaBundle;
import com.solace.schemavalidator.registry.SchemaDocument;
import com.solace.schemavalidator.registry.SchemaVersionCoordinate;
import com.solace.schemavalidator.resolution.ArtifactType;
import com.squareup.wire.ProtoAdapter;
import com.squareup.wire.schema.Location;
import com.squareup.wire.schema.Schema;
import com.squareup.wire.schema.SchemaLoader;
import com.squareup.wire.schema.SchemaProtoAdapterFactory;
import com.squareup.wire.schema.ProtoType;
import com.squareup.wire.schema.MessageType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import okio.Buffer;
import org.springframework.stereotype.Component;

/**
 * Validates binary Protobuf payloads against a .proto schema stored in the registry.
 *
 * <p>Uses Square Wire's {@link SchemaLoader} to parse .proto source text at compile time —
 * no {@code protoc} toolchain required. The compiled {@link SchemaProtoAdapterFactory} is then
 * used to decode each payload against the root message type.
 *
 * <p>The root message type is resolved as follows (first match wins):
 * <ol>
 *   <li>A {@code // @root MessageName} directive on the first line of the root .proto file.</li>
 *   <li>The first {@code message} declaration in the root .proto file.</li>
 * </ol>
 *
 * <p>The message type name must be fully qualified if it lives in a package
 * (e.g. {@code com.example.MyMessage}).
 */
@Component
public class ProtobufSchemaValidatorFactory implements ValidatorFactory {

    @Override
    public ArtifactType supportedType() {
        return ArtifactType.PROTOBUF;
    }

    @Override
    public CompiledValidator compile(SchemaBundle schemaBundle) {
        Path tempDir = null;
        try {
            tempDir = Files.createTempDirectory("proto-schema-");
            writeProtoFiles(schemaBundle, tempDir);

            List<Location> sourcePaths = List.of(Location.get(tempDir.toString(), protoFileName(schemaBundle.root())));
            SchemaLoader loader = new SchemaLoader(tempDir.getFileSystem());
            loader.initRoots(sourcePaths, List.of(Location.get(tempDir.toString())));
            Schema schema = loader.loadSchema();

            String rootTypeName = resolveRootMessageType(schemaBundle.root(), schema);
            ProtoType protoType = ProtoType.get(rootTypeName);

            SchemaProtoAdapterFactory adapterFactory = new SchemaProtoAdapterFactory(schema, true);
            @SuppressWarnings("unchecked")
            ProtoAdapter<Object> adapter = (ProtoAdapter<Object>) adapterFactory.get(protoType);

            return payload -> {
                try {
                    Object decoded = adapter.decode(new Buffer().write(payload));
                    String missing = missingRequiredField(schema, protoType, decoded, "");
                    if (missing != null) {
                        return ValidationOutcome.failure("Missing required Protobuf field: " + missing);
                    }
                    return ValidationOutcome.success();
                } catch (Exception ex) {
                    return ValidationOutcome.failure("Protobuf payload could not be decoded: " + ex.getMessage());
                }
            };
        } catch (IllegalStateException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to compile Protobuf validator for "
                + schemaBundle.root().coordinate(), ex);
        } finally {
            deleteQuietly(tempDir);
        }
    }

    private static String missingRequiredField(Schema schema, ProtoType type, Object value, String path) {
        if (!(schema.getType(type) instanceof MessageType message) || !(value instanceof Map<?, ?> fields)) {
            return null;
        }
        for (var field : message.getFieldsAndOneOfFields()) {
            Object fieldValue = fields.get(field.getName());
            String fieldPath = path + field.getName();
            if (field.isRequired() && fieldValue == null) {
                return fieldPath;
            }
            if (field.getType().isMap() && fieldValue instanceof Map<?, ?> map) {
                for (var entry : map.entrySet()) {
                    String missing = missingRequiredField(schema, field.getType().getValueType(),
                        entry.getValue(), fieldPath + "[" + entry.getKey() + "].");
                    if (missing != null) return missing;
                }
            } else if (fieldValue instanceof List<?> repeated) {
                for (int i = 0; i < repeated.size(); i++) {
                    String missing = missingRequiredField(schema, field.getType(), repeated.get(i), fieldPath + "[" + i + "].");
                    if (missing != null) return missing;
                }
            } else if (fieldValue != null) {
                String missing = missingRequiredField(schema, field.getType(), fieldValue, fieldPath + ".");
                if (missing != null) return missing;
            }
        }
        return null;
    }

    private static void writeProtoFiles(SchemaBundle bundle, Path dir) throws IOException {
        Map<String, SchemaDocument> files = new LinkedHashMap<>();
        files.put(protoFileName(bundle.root()), bundle.root());
        for (SchemaDocument document : bundle.compilationOrder()) {
            for (var reference : document.references()) {
                SchemaDocument referenced = bundle.documents().get(new SchemaVersionCoordinate(
                    reference.groupId(), reference.artifactId(), reference.version()));
                if (referenced == null) {
                    throw new IllegalStateException("Missing Protobuf reference: " + reference.name());
                }
                SchemaDocument previous = files.putIfAbsent(reference.name(), referenced);
                if (previous != null && !previous.coordinate().equals(referenced.coordinate())) {
                    throw new IllegalStateException("Conflicting Protobuf import name: " + reference.name());
                }
            }
        }
        for (var entry : files.entrySet()) {
            String fileName = entry.getKey();
            Path file = dir.resolve(fileName).normalize();
            if (!file.startsWith(dir)) {
                throw new IllegalStateException("Protobuf artifact path escapes the schema directory: " + fileName);
            }
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.getValue().content());
        }
    }

    private static String protoFileName(SchemaDocument document) {
        String artifactId = document.coordinate().artifactId();
        return artifactId.endsWith(".proto") ? artifactId : artifactId + ".proto";
    }

    /**
     * Derives the root Protobuf message type name from the root schema document.
     * Checks for an optional {@code // @root MessageName} directive on the first line,
     * otherwise returns the name from the first {@code message} declaration.
     */
    static String resolveRootMessageType(SchemaDocument root, Schema schema) {
        String content = root.content();
        String[] lines = content.split("\n", -1);
        // Check for explicit directive on first non-empty line
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (trimmed.startsWith("// @root ")) {
                return trimmed.substring("// @root ".length()).trim();
            }
            break;
        }
        // Use Wire's parsed model so legal whitespace, comments, enum declarations,
        // and braces on a separate line do not affect root inference.
        var rootFile = schema.protoFile(protoFileName(root));
        if (rootFile != null) {
            for (var type : rootFile.getTypes()) {
                if (type instanceof MessageType) {
                    return type.getType().toString();
                }
            }
        }
        throw new IllegalStateException(
            "Cannot determine root message type for Protobuf artifact '"
                + root.coordinate().artifactId()
                + "'. Add '// @root fully.qualified.MessageName' as the first line of the .proto file.");
    }

    private static void deleteQuietly(Path dir) {
        if (dir == null) {
            return;
        }
        try (var stream = Files.walk(dir)) {
            stream.sorted(Comparator.reverseOrder())
                .forEach(p -> { try { Files.delete(p); } catch (IOException ignored) { } });
        } catch (IOException ignored) {
        }
    }
}
