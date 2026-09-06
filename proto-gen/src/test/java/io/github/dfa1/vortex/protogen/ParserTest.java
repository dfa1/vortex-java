package io.github.dfa1.vortex.protogen;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParserTest {

    @Nested
    class Synthetic {

        @Test
        void parsesMinimalMessage() {
            // Given
            String src = """
                    syntax = "proto3";
                    package vortex.test;
                    option java_package = "io.github.dfa1.vortex.core.proto";
                    message Foo {
                        uint32 bit_width = 1;
                        optional bytes meta = 2;
                        repeated string names = 3;
                    }
                    """;

            // When
            Ast.ProtoFile sut = new Parser(new Lexer(src).tokenize()).parseFile();

            // Then
            assertThat(sut.protoPackage()).isEqualTo("vortex.test");
            assertThat(sut.javaPackage()).isEqualTo("io.github.dfa1.vortex.core.proto");
            assertThat(sut.decls()).hasSize(1);
            Ast.MessageDecl foo = (Ast.MessageDecl) sut.decls().get(0);
            assertThat(foo.name()).isEqualTo("Foo");
            assertThat(foo.members()).hasSize(3);
            Ast.FieldDecl f0 = (Ast.FieldDecl) foo.members().get(0);
            assertThat(f0.name()).isEqualTo("bit_width");
            assertThat(f0.type()).isEqualTo(Ast.Scalar.UINT32);
            assertThat(f0.rule()).isEqualTo(Ast.Rule.SINGLE);
            assertThat(f0.number()).isEqualTo(1);
            Ast.FieldDecl f1 = (Ast.FieldDecl) foo.members().get(1);
            assertThat(f1.rule()).isEqualTo(Ast.Rule.OPTIONAL);
            assertThat(f1.type()).isEqualTo(Ast.Scalar.BYTES);
            Ast.FieldDecl f2 = (Ast.FieldDecl) foo.members().get(2);
            assertThat(f2.rule()).isEqualTo(Ast.Rule.REPEATED);
            assertThat(f2.type()).isEqualTo(Ast.Scalar.STRING);
        }

        @Test
        void parsesOneOf() {
            // Given
            String src = """
                    syntax = "proto3";
                    message Scalar {
                        oneof kind {
                            bool bool_value = 1;
                            sint64 int64_value = 2;
                            string string_value = 3;
                        }
                    }
                    """;

            // When
            Ast.ProtoFile sut = new Parser(new Lexer(src).tokenize()).parseFile();

            // Then
            Ast.MessageDecl msg = (Ast.MessageDecl) sut.decls().get(0);
            Ast.OneOfDecl one = (Ast.OneOfDecl) msg.members().get(0);
            assertThat(one.name()).isEqualTo("kind");
            assertThat(one.fields()).hasSize(3);
            assertThat(one.fields().get(1).type()).isEqualTo(Ast.Scalar.SINT64);
        }

        @Test
        void parsesEnumAndQualifiedRef() {
            // Given
            String src = """
                    syntax = "proto3";
                    enum PType { U8 = 0; I64 = 7; }
                    message X {
                        vortex.dtype.PType ptype = 1;
                    }
                    """;

            // When
            Ast.ProtoFile sut = new Parser(new Lexer(src).tokenize()).parseFile();

            // Then
            Ast.EnumDecl e = (Ast.EnumDecl) sut.decls().get(0);
            assertThat(e.values()).extracting(Ast.EnumValue::name).containsExactly("U8", "I64");
            assertThat(e.values()).extracting(Ast.EnumValue::number).containsExactly(0, 7);
            Ast.MessageDecl m = (Ast.MessageDecl) sut.decls().get(1);
            Ast.FieldDecl f = (Ast.FieldDecl) m.members().get(0);
            assertThat(((Ast.Ref) f.type()).typeName()).isEqualTo("vortex.dtype.PType");
        }

        @Test
        void rejectsProto2() {
            // Given
            String src = """
                    syntax = "proto2";
                    message Foo {}
                    """;
            Parser parser = new Parser(new Lexer(src).tokenize());

            // When + Then
            assertThatThrownBy(parser::parseFile)
                    .isInstanceOf(ProtoParseException.class)
                    .hasMessageContaining("proto3");
        }
    }

    @Nested
    class VortexSources {

        @Test
        void parsesDtypeProto() throws Exception {
            // Given
            String src = Files.readString(repoRoot().resolve("core/src/main/proto/dtype.proto"));

            // When
            Ast.ProtoFile sut = new Parser(new Lexer(src).tokenize()).parseFile();

            // Then
            assertThat(sut.protoPackage()).isEqualTo("vortex.dtype");
            assertThat(sut.javaPackage()).isEqualTo("io.github.dfa1.vortex.core.proto");
            assertThat(sut.decls()).extracting(d ->
                    switch (d) {
                        case Ast.MessageDecl m -> m.name();
                        case Ast.EnumDecl e -> e.name();
                    }
            ).contains("DType", "PType", "Struct", "Extension", "FieldPath");
        }

        @Test
        void parsesScalarProto() throws Exception {
            // Given
            String src = Files.readString(repoRoot().resolve("core/src/main/proto/scalar.proto"));

            // When
            Ast.ProtoFile sut = new Parser(new Lexer(src).tokenize()).parseFile();

            // Then
            assertThat(sut.imports()).contains("dtype.proto", "google/protobuf/struct.proto");
            assertThat(sut.decls()).extracting(d -> ((Ast.MessageDecl) d).name())
                    .containsExactly("Scalar", "ScalarValue", "ListValue");
        }

        @Test
        void parsesEncodingsProto() throws Exception {
            // Given
            String src = Files.readString(repoRoot().resolve("core/src/main/proto/encodings.proto"));

            // When
            Ast.ProtoFile sut = new Parser(new Lexer(src).tokenize()).parseFile();

            // Then — sanity-check the message count + a known field.
            assertThat(sut.decls()).hasSizeGreaterThanOrEqualTo(15);
            Ast.MessageDecl alp = (Ast.MessageDecl) sut.decls().stream()
                    .filter(d -> d instanceof Ast.MessageDecl m && m.name().equals("ALPMetadata"))
                    .findFirst().orElseThrow();
            assertThat(alp.members()).extracting(m -> ((Ast.FieldDecl) m).name())
                    .containsExactly("exp_e", "exp_f", "patches");
        }
    }

    private static Path repoRoot() {
        // Test runs from proto-gen/, repo root is two levels up.
        return Path.of("").toAbsolutePath().getParent();
    }
}
