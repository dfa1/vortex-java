package io.github.dfa1.vortex.fbsgen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CodeGenTest {

    @Test
    void vectorOfUnions_throwsFbsParseException(@TempDir Path out) {
        // Given a schema whose table field is a vector of a union type — the one array-element
        // shape the FlatBuffers layout cannot express, so the generator must reject it.
        String schema = """
                namespace x;
                table A { a: int; }
                table B { b: int; }
                union U { A, B }
                table T { items: [U]; }
                """;
        Ast.SchemaFile file = new Parser(new Lexer(schema).tokenize()).parseFile();
        CodeGen sut = new CodeGen(new TypeRegistry(List.of(file)));

        // When / Then
        assertThatThrownBy(() -> sut.emit(out))
                .isInstanceOf(FbsParseException.class)
                .hasMessageContaining("vectors of unions are not supported");
    }

    @Test
    void emitsOneFilePerDeclarationForAllVortexSchemas(@TempDir Path out) throws IOException {
        // Given + When
        generate(out);

        // Then — a representative file from each schema exists, with a package line
        // before any import (the ordering bug guard).
        assertThat(Files.exists(out.resolve("FbsArray.java"))).isTrue();
        assertThat(Files.exists(out.resolve("FbsDType.java"))).isTrue();
        assertThat(Files.exists(out.resolve("FbsFooter.java"))).isTrue();
        assertThat(Files.exists(out.resolve("FbsLayout.java"))).isTrue();
        String layout = Files.readString(out.resolve("FbsLayout.java"));
        assertThat(layout.indexOf("package ")).isLessThan(layout.indexOf("import "));
    }

    @Test
    void emitsScalarVectorAndChildAccessorsForTable() throws IOException {
        // Given — Layout has u16/u64 scalars, a byte vector, a table-vector and a u32 vector.
        String src = generateOne("FbsLayout.java");

        // Then — accessors match the flatc read shapes (widened unsigned types).
        assertThat(src).contains("extends FbsTable");
        assertThat(src).contains("public int encoding() {");
        assertThat(src).contains("public long rowCount() {");
        assertThat(src).contains("public MemorySegment metadataAsSegment() {");
        assertThat(src).contains("public int childrenLength() {");
        assertThat(src).contains("public FbsLayout children(int j) {");
        assertThat(src).contains("indirect(vectorElements(o) + (long) j * 4)");
        assertThat(src).contains("public long segments(int j) {");
        assertThat(src).contains("readInt(vectorElements(o) + (long) j * 4) & 0xFFFFFFFFL");
    }

    @Test
    void emitsInlineOffsetsForStruct() throws IOException {
        // Given — Buffer struct: padding u16 @0, alignment u8 @2, compression(enum u8) @3,
        // length u32 @4.
        String src = generateOne("FbsBuffer.java");

        // Then
        assertThat(src).contains("extends FbsStruct");
        assertThat(src).contains("8 bytes");
        assertThat(src).contains("public int padding() {\n        return readShort(0) & 0xFFFF;");
        assertThat(src).contains("public int alignmentExponent() {\n        return readByte(2) & 0xFF;");
        assertThat(src).contains("public int compression() {\n        return readByte(3) & 0xFF;");
        assertThat(src).contains("public long length() {\n        return readInt(4) & 0xFFFFFFFFL;");
    }

    @Test
    void emitsDiscriminatorAndMemberPositionForUnionField() throws IOException {
        // Given — DType has a single union field `type`.
        String src = generateOne("FbsDType.java");

        // Then — discriminator at vtable slot 4, value (member) projected from slot 6.
        assertThat(src).contains("public int typeType() {");
        assertThat(src).contains("fieldOffset(4)");
        assertThat(src).contains("public <T extends FbsTable> T type(T obj) {");
        assertThat(src).contains("locate(obj, unionMemberPosition(o))");
        assertThat(src).contains("fieldOffset(6)");
    }

    @Test
    void emitsUnionConstantsHolder() throws IOException {
        // Given
        String src = generateOne("FbsType.java");

        // Then — NONE plus members with explicit discriminators (byte, like flatc).
        assertThat(src).contains("public static final byte NONE = 0;");
        assertThat(src).contains("public static final byte FbsNull = (byte) 1;");
        assertThat(src).contains("public static final byte FbsUnion = (byte) 12;");
    }

    @Test
    void emitsEnumConstantsOverUnderlyingType() throws IOException {
        // Given — PType: uint8 with auto-incremented values.
        String src = generateOne("FbsPType.java");

        // Then — byte constants, auto-numbered from 0.
        assertThat(src).contains("public static final byte U8 = (byte) 0;");
        assertThat(src).contains("public static final byte F64 = (byte) 10;");
    }

    private static void generate(Path out) throws IOException {
        Path root = repoRoot();
        List<Ast.SchemaFile> files = List.of(
                parse(root.resolve("core/src/main/fbs/array.fbs")),
                parse(root.resolve("core/src/main/fbs/dtype.fbs")),
                parse(root.resolve("core/src/main/fbs/footer.fbs")),
                parse(root.resolve("core/src/main/fbs/layout.fbs"))
        );
        new CodeGen(new TypeRegistry(files)).emit(out);
    }

    private static String generateOne(String fileName) throws IOException {
        Path out = Files.createTempDirectory("fbsgen");
        generate(out);
        return Files.readString(out.resolve(fileName));
    }

    private static Ast.SchemaFile parse(Path p) throws IOException {
        return new Parser(new Lexer(Files.readString(p)).tokenize()).parseFile();
    }

    private static Path repoRoot() {
        return Path.of("").toAbsolutePath().getParent();
    }
}
