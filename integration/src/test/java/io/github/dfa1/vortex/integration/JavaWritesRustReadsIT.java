package io.github.dfa1.vortex.integration;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Cross-compatibility: Java writer → Rust (JNI) reader.
///
/// Tests are disabled until the JNI vortex bindings are available.
/// See TODO.md #9 and the commented-out `vortex-jni` dep in `it/pom.xml`.
@Disabled("blocked by JNI bindings — see TODO.md #9")
class JavaWritesRustReadsIT {

    private static final DType.Struct SCHEMA = new DType.Struct(
        List.of("id", "value"),
        List.of(new DType.Primitive(PType.I64, false),
                new DType.Primitive(PType.F64, false)),
        false);

    @Test
    void javaWriter_rustReader_singleChunk(@TempDir Path tmp) throws IOException {
        // Given
        Path     file = tmp.resolve("java_written.vtx");
        long[]   ids  = {1L, 2L, 3L};
        double[] vals = {1.1, 2.2, 3.3};

        // When
        try (var ch  = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults())) {
            sut.writeChunk(Map.of("id", ids, "value", vals));
        }

        // Then
        // TODO: replace with JNI call once bindings are available
        // long[]   decodedIds  = VortexJni.readColumn(file, "id",    long[].class);
        // double[] decodedVals = VortexJni.readColumn(file, "value", double[].class);
        // assertThat(decodedIds).containsExactly(1L, 2L, 3L);
        // assertThat(decodedVals).containsExactly(1.1, 2.2, 3.3);
        throw new UnsupportedOperationException("JNI reader not available");
    }

    @Test
    void javaWriter_rustReader_multipleChunks(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("java_written_multi.vtx");

        // When
        try (var ch  = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults())) {
            sut.writeChunk(Map.of("id", new long[]{1L, 2L},      "value", new double[]{1.1, 2.2}));
            sut.writeChunk(Map.of("id", new long[]{3L, 4L, 5L},  "value", new double[]{3.3, 4.4, 5.5}));
        }

        // Then
        // TODO: assert JNI reader sees 5 total rows across 2 chunks
        throw new UnsupportedOperationException("JNI reader not available");
    }
}
