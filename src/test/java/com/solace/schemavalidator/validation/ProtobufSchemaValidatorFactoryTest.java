package com.solace.schemavalidator.validation;

import com.solace.schemavalidator.registry.*;
import com.solace.schemavalidator.resolution.ArtifactType;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ProtobufSchemaValidatorFactoryTest {
    @Test
    void enforcesProto2RequiredFields() {
        var root = doc("required", """
            syntax = "proto2";
            message Reading { required string name = 1; }
            """);
        var validator = new ProtobufSchemaValidatorFactory().compile(new SchemaBundle(root,
            Map.of(root.coordinate(), root), List.of(root)));
        assertThat(validator.validate(new byte[]{10, 1, 65}).valid()).isTrue();
        assertThat(validator.validate(new byte[]{}).valid()).isFalse();
    }
    @Test
    void infersMessageFromParsedSchemaDespiteFormattingAndComments() {
        var root = doc("formatted", """
            syntax = "proto3"; package formatted;
            /* message Fake { string ignored = 1; } */
            enum Status { UNKNOWN = 0; }
            message Reading
            {
              string value = 1;
            }
            """);
        var validator = new ProtobufSchemaValidatorFactory().compile(new SchemaBundle(root,
            Map.of(root.coordinate(), root), List.of(root)));
        assertThat(validator.validate(new byte[]{10, 1, 65}).valid()).isTrue();
    }

    @Test
    void supportsWellKnownGoogleProtobufTypes() {
        var root = doc("timestamped", """
            syntax = "proto3";
            import "google/protobuf/timestamp.proto";
            message Reading { google.protobuf.Timestamp timestamp = 1; }
            """);
        var validator = new ProtobufSchemaValidatorFactory().compile(new SchemaBundle(root,
            Map.of(root.coordinate(), root), List.of(root)));
        assertThat(validator.validate(new byte[]{10, 2, 8, 1}).valid()).isTrue();
    }
    private SchemaDocument doc(String id, String content, ArtifactReference... references) {
        return new SchemaDocument(new SchemaVersionCoordinate("test", id, "1"), ArtifactType.PROTOBUF,
            content, List.of(references));
    }

    @Test
    void validatesBinaryProtobufWithRegistryImportAliases() {
        var child = doc("different-artifact-id", """
            syntax = "proto3";
            package test;
            message Child { string name = 1; }
            """);
        var root = doc("root", """
            syntax = "proto3";
            package test;
            import "shared/child.proto";
            message Root { Child child = 1; }
            """, new ArtifactReference("test", "different-artifact-id", "1", "shared/child.proto"));
        var bundle = new SchemaBundle(root, Map.of(root.coordinate(), root, child.coordinate(), child), List.of(child, root));
        var validator = new ProtobufSchemaValidatorFactory().compile(bundle);
        assertThat(validator.validate(new byte[]{10, 3, 10, 1, 65}).valid()).isTrue();
        assertThat(validator.validate(new byte[]{10, 5, 10}).valid()).isFalse();
    }

    @Test
    void rejectsImportPathsOutsideTheTemporaryDirectory() {
        var child = doc("child", "syntax = \"proto3\"; message Child {}");
        var root = doc("root", "syntax = \"proto3\"; message Root {}",
            new ArtifactReference("test", "child", "1", "../escape.proto"));
        var bundle = new SchemaBundle(root, Map.of(root.coordinate(), root, child.coordinate(), child), List.of(child, root));
        assertThatThrownBy(() -> new ProtobufSchemaValidatorFactory().compile(bundle))
            .isInstanceOf(IllegalStateException.class).hasMessageContaining("escapes");
    }
}
