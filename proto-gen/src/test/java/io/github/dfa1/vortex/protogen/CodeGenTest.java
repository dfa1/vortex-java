package io.github.dfa1.vortex.protogen;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CodeGenTest {

    @Test
    void generatesJavaFromAllVortexProtos(@TempDir Path tmp) throws IOException {
        // Given
        Path root = repoRoot();
        List<Ast.ProtoFile> files = List.of(
                parse(root.resolve("core/src/main/proto/dtype.proto")),
                parse(root.resolve("core/src/main/proto/scalar.proto")),
                parse(root.resolve("core/src/main/proto/encodings.proto"))
        );
        CodeGen sut = new CodeGen(new TypeRegistry(files));

        // When
        sut.emit(tmp);

        // Then — a few expected files were emitted.
        assertThat(Files.exists(tmp.resolve("PType.java"))).isTrue();
        assertThat(Files.exists(tmp.resolve("DType.java"))).isTrue();
        assertThat(Files.exists(tmp.resolve("ScalarValue.java"))).isTrue();
        assertThat(Files.exists(tmp.resolve("BitPackedMetadata.java"))).isTrue();
        assertThat(Files.exists(tmp.resolve("NullValue.java"))).isTrue();
    }

    @Test
    void generatedBitPackedMetadataHasExpectedComponents(@TempDir Path tmp) throws IOException {
        // Given
        Path root = repoRoot();
        List<Ast.ProtoFile> files = List.of(
                parse(root.resolve("core/src/main/proto/dtype.proto")),
                parse(root.resolve("core/src/main/proto/encodings.proto")),
                parse(root.resolve("core/src/main/proto/scalar.proto"))
        );
        new CodeGen(new TypeRegistry(files)).emit(tmp);

        // When
        String src = Files.readString(tmp.resolve("BitPackedMetadata.java"));

        // Then
        assertThat(src).contains("public record BitPackedMetadata(");
        assertThat(src).contains("int bit_width");
        assertThat(src).contains("int offset");
        assertThat(src).contains("PatchesMetadata patches");
        assertThat(src).contains("public static BitPackedMetadata decode(MemorySegment __seg, long __off, long __len)");
        assertThat(src).contains("public byte[] encode()");
    }

    private static Ast.ProtoFile parse(Path p) throws IOException {
        return new Parser(new Lexer(Files.readString(p)).tokenize()).parseFile();
    }

    private static Path repoRoot() {
        return Path.of("").toAbsolutePath().getParent();
    }
}
