package com.solace.schemavalidator.validation;

import com.solace.schemavalidator.registry.SchemaBundle;
import com.solace.schemavalidator.registry.SchemaDocument;
import com.solace.schemavalidator.resolution.ArtifactType;
import com.squareup.wire.ProtoAdapter;
import com.squareup.wire.schema.Location;
import com.squareup.wire.schema.Schema;
import com.squareup.wire.schema.SchemaLoader;
import com.squareup.wire.schema.SchemaProtoAdapterFactory;
import com.squareup.wire.schema.ProtoType;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
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

            List<Location> sourcePaths = List.of(Location.get(tempDir.toString()));
            SchemaLoader loader = new SchemaLoader(tempDir.getFileSystem());
            loader.initRoots(sourcePaths, List.of());
            Schema schema = loader.loadSchema();

            String rootTypeName = resolveRootMessageType(schemaBundle.root());
            ProtoType protoType = ProtoType.get(rootTypeName);

            SchemaProtoAdapterFactory adapterFactory = new SchemaProtoAdapterFactory(schema, true);
            @SuppressWarnings("unchecked")
            ProtoAdapter<Object> adapter = (ProtoAdapter<Object>) adapterFactory.get(protoType);

            return payload -> {
                try {
                    adapter.decode(new Buffer().write(payload));
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

    private static void writeProtoFiles(SchemaBundle bundle, Path dir) throws IOException {
        for (SchemaDocument document : bundle.compilationOrder()) {
            String fileName = protoFileName(document);
            Path file = dir.resolve(fileName);
            Files.createDirectories(file.getParent());
            Files.writeString(file, document.content());
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
    static String resolveRootMessageType(SchemaDocument root) {
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
        // Extract package prefix (if present) for fully-qualified name
        String packageName = null;
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("package ") && trimmed.endsWith(";")) {
                packageName = trimmed.substring("package ".length(), trimmed.length() - 1).trim();
                break;
            }
        }
        // Find first message declaration
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("message ")) {
                String rest = trimmed.substring("message ".length()).trim();
                int end = rest.indexOf('{');
                if (end > 0) {
                    String simpleName = rest.substring(0, end).trim();
                    return packageName != null ? packageName + "." + simpleName : simpleName;
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
