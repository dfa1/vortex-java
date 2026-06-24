package io.github.dfa1.vortex.fbsgen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ParserTest {

    @Nested
    class VortexSchemas {

        /// The four real vortex schemas must all parse — this is the spike's ground
        /// truth: the parser has to cover everything actually used in the format,
        /// not a synthetic subset.
        @ParameterizedTest
        @ValueSource(strings = {
                "core/src/main/fbs/array.fbs",
                "core/src/main/fbs/dtype.fbs",
                "core/src/main/fbs/footer.fbs",
                "core/src/main/fbs/layout.fbs"
        })
        void parsesEveryVortexSchema(String relPath) throws IOException {
            // Given
            Ast.SchemaFile sut = parse(repoRoot().resolve(relPath));

            // Then — every schema shares this namespace and declares at least one type.
            assertThat(sut.namespace()).isEqualTo("io.github.dfa1.vortex.core.fbs");
            assertThat(sut.decls()).isNotEmpty();
        }

        @Test
        void parsesDtypeUnionAndTable() throws IOException {
            // Given — dtype.fbs is the richest: enum-with-underlying, a 12-member union,
            // and tables that reference each other recursively.
            Ast.SchemaFile sut = parse(repoRoot().resolve("core/src/main/fbs/dtype.fbs"));

            // Then — the underlying-typed enum is captured with all 11 PType values.
            Ast.EnumDecl ptype = (Ast.EnumDecl) declNamed(sut, "PType");
            assertThat(ptype.underlying()).isEqualTo(Ast.Scalar.UINT8);
            assertThat(ptype.values()).hasSize(11);

            // And — the union is captured with its 12 members and explicit discriminators.
            Ast.UnionDecl type = (Ast.UnionDecl) declNamed(sut, "Type");
            assertThat(type.members()).hasSize(12);
            assertThat(type.members().getFirst().typeName()).isEqualTo("Null");
            assertThat(type.members().getFirst().number()).hasValue(1);

            // And — the DType table holds a single union-typed field.
            Ast.TableDecl dtype = (Ast.TableDecl) declNamed(sut, "DType");
            assertThat(dtype.fields()).singleElement()
                    .satisfies(f -> assertThat(((Ast.Ref) f.type()).typeName()).isEqualTo("Type"));
        }

        @Test
        void parsesArrayStructVectorAndDefaults() throws IOException {
            // Given — array.fbs exercises a struct (inline), vectors, an enum field,
            // and `bool = null` three-state defaults.
            Ast.SchemaFile sut = parse(repoRoot().resolve("core/src/main/fbs/array.fbs"));

            // Then — Buffer is a struct, and its compression field references the enum.
            Ast.StructDecl buffer = (Ast.StructDecl) declNamed(sut, "Buffer");
            Ast.Field compression = fieldNamed(buffer.fields(), "compression");
            assertThat(((Ast.Ref) compression.type()).typeName()).isEqualTo("Compression");

            // And — Array.buffers is a vector of Buffer.
            Ast.TableDecl array = (Ast.TableDecl) declNamed(sut, "Array");
            Ast.Field buffers = fieldNamed(array.fields(), "buffers");
            assertThat(((Ast.Ref) ((Ast.Vector) buffers.type()).element()).typeName()).isEqualTo("Buffer");

            // And — ArrayStats keeps the `= null` defaults verbatim for tri-state bools.
            Ast.TableDecl stats = (Ast.TableDecl) declNamed(sut, "ArrayStats");
            assertThat(fieldNamed(stats.fields(), "is_sorted").defaultValue()).hasValue("null");
        }

        @Test
        void capturesIncludesAndRootTypes() throws IOException {
            // Given — footer.fbs includes the other schemas and declares three root types.
            Ast.SchemaFile sut = parse(repoRoot().resolve("core/src/main/fbs/footer.fbs"));

            // Then
            assertThat(sut.includes()).containsExactly("array.fbs", "layout.fbs");
            assertThat(sut.rootTypes()).containsExactly("FileStatistics", "Footer", "Postscript");
        }
    }

    @Nested
    class Synthetic {

        @Test
        void normalizesScalarAliasesToCanonicalWidths() {
            // Given — width aliases that must collapse to the same canonical scalars.
            String src = """
                    namespace x;
                    table T {
                        a: byte;
                        b: int8;
                        c: ulong;
                        d: uint64;
                    }
                    """;

            // When
            Ast.SchemaFile sut = new Parser(new Lexer(src).tokenize()).parseFile();

            // Then — byte == int8 == INT8, ulong == uint64 == UINT64.
            Ast.TableDecl t = (Ast.TableDecl) sut.decls().getFirst();
            assertThat(t.fields().get(0).type()).isEqualTo(Ast.Scalar.INT8);
            assertThat(t.fields().get(1).type()).isEqualTo(Ast.Scalar.INT8);
            assertThat(t.fields().get(2).type()).isEqualTo(Ast.Scalar.UINT64);
            assertThat(t.fields().get(3).type()).isEqualTo(Ast.Scalar.UINT64);
        }

        @Test
        void honorsRequiredAttribute() {
            // Given — a `(required)` string field.
            String src = "namespace x; table T { id: string (required); }";

            // When
            Ast.SchemaFile sut = new Parser(new Lexer(src).tokenize()).parseFile();

            // Then
            Ast.TableDecl t = (Ast.TableDecl) sut.decls().getFirst();
            assertThat(t.fields().getFirst().required()).isTrue();
        }

        @Test
        void rejectsUnterminatedTable() {
            // Given — a table missing its closing brace runs into EOF.
            String src = "namespace x; table T { a: int;";

            // When + Then
            assertThatThrownBy(() -> new Parser(new Lexer(src).tokenize()).parseFile())
                    .isInstanceOf(FbsParseException.class);
        }
    }

    private static Ast.SchemaFile parse(Path p) throws IOException {
        return new Parser(new Lexer(Files.readString(p)).tokenize()).parseFile();
    }

    private static Path repoRoot() {
        return Path.of("").toAbsolutePath().getParent();
    }

    private static Ast.Decl declNamed(Ast.SchemaFile file, String name) {
        return file.decls().stream()
                .filter(d -> d.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no decl named " + name));
    }

    private static Ast.Field fieldNamed(java.util.List<Ast.Field> fields, String name) {
        return fields.stream()
                .filter(f -> f.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no field named " + name));
    }
}
