package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.Array;
import io.github.dfa1.vortex.encoding.DecoderRegistry;
import io.github.dfa1.vortex.encoding.PrimitiveCodec;
import io.github.dfa1.vortex.io.VortexFile;
import io.github.dfa1.vortex.scan.ScanOptions;
import io.github.dfa1.vortex.scan.ScanResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VortexWriterTest {

    private static final DType.Struct SCHEMA = new DType.Struct(
        List.of("id", "value"),
        List.of(new DType.Primitive(PType.I64, false),
                new DType.Primitive(PType.F64, false)),
        false);

    // ── writeChunk validation ─────────────────────────────────────────────────

    @Test
    void writeChunk_missingColumn_throwsIllegalArgument(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("missing.vtx");
        try (var ch  = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults())) {
            // When / Then
            assertThatThrownBy(() -> sut.writeChunk(Map.of("id", new long[]{1L})))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing column: value");
        }
    }

    // ── Round-trip: write then read ───────────────────────────────────────────

    @Test
    void writeAndRead_singleChunk_returnsCorrectRowCount(@TempDir Path tmp) throws IOException {
        // Given
        Path     file = tmp.resolve("single.vtx");
        long[]   ids  = {1L, 2L, 3L};
        double[] vals = {1.0, 2.0, 3.0};

        try (var ch  = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults())) {
            // When
            sut.writeChunk(Map.of("id", ids, "value", vals));
        }

        // Then
        var registry = primitiveRegistry();
        try (var vf = VortexFile.open(file, registry)) {
            List<ScanResult> results = scanAll(vf, ScanOptions.all());
            assertThat(results).hasSize(1);
            assertThat(results.get(0).rowCount()).isEqualTo(3L);
            assertThat(results.get(0).columns()).containsKeys("id", "value");
        }
    }

    @Test
    void writeAndRead_multipleChunks_returnsAllChunks(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("multi.vtx");

        try (var ch  = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults())) {
            // When
            sut.writeChunk(Map.of("id", new long[]{1L, 2L}, "value", new double[]{1.0, 2.0}));
            sut.writeChunk(Map.of("id", new long[]{3L, 4L, 5L}, "value", new double[]{3.0, 4.0, 5.0}));
        }

        // Then
        var registry = primitiveRegistry();
        try (var vf = VortexFile.open(file, registry)) {
            List<ScanResult> results = scanAll(vf, ScanOptions.all());
            assertThat(results).hasSize(2);
            assertThat(results.get(0).rowCount()).isEqualTo(2L);
            assertThat(results.get(1).rowCount()).isEqualTo(3L);
        }
    }

    @Test
    void writeAndRead_idValues_decodedCorrectly(@TempDir Path tmp) throws IOException {
        // Given
        Path   file = tmp.resolve("values.vtx");
        long[] ids  = {42L, 100L, -1L};

        try (var ch  = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults())) {
            // When
            sut.writeChunk(Map.of("id", ids, "value", new double[]{0.0, 0.0, 0.0}));
        }

        // Then
        var registry = primitiveRegistry();
        try (var vf = VortexFile.open(file, registry)) {
            List<ScanResult> results = scanAll(vf, ScanOptions.all());
            assertThat(results).hasSize(1);
            Array idArray = results.get(0).columns().get("id");
            assertThat(idArray.length()).isEqualTo(3L);
            MemorySegment buf = idArray.buffer(0);
            assertThat(buf.get(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), 0)).isEqualTo(42L);
            assertThat(buf.get(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), 8)).isEqualTo(100L);
            assertThat(buf.get(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN), 16)).isEqualTo(-1L);
        }
    }

    @Test
    void writeAndRead_columnProjection_returnsOnlyRequestedColumns(@TempDir Path tmp)
        throws IOException {
        // Given
        Path file = tmp.resolve("proj.vtx");

        try (var ch  = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults())) {
            // When
            sut.writeChunk(Map.of("id", new long[]{1L}, "value", new double[]{9.9}));
        }

        // Then
        var registry = primitiveRegistry();
        try (var vf = VortexFile.open(file, registry)) {
            List<ScanResult> results = scanAll(vf, ScanOptions.columns("id"));
            assertThat(results).hasSize(1);
            assertThat(results.get(0).columns()).containsKey("id");
            assertThat(results.get(0).columns()).doesNotContainKey("value");
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static List<ScanResult> scanAll(VortexFile vf, ScanOptions opts) throws IOException {
        var results = new ArrayList<ScanResult>();
        var iter    = vf.scan(opts);
        while (iter.hasNext()) {
            results.add(iter.next());
        }
        return results;
    }

    private static DecoderRegistry primitiveRegistry() {
        var registry = DecoderRegistry.empty();
        registry.register(new PrimitiveCodec());
        return registry;
    }
}
