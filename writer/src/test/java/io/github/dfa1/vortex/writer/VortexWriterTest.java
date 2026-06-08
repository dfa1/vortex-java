package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.ArraySegments;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.encoding.AlpEncoding;
import io.github.dfa1.vortex.encoding.EncodingRegistry;
import io.github.dfa1.vortex.encoding.PrimitiveEncoding;
import io.github.dfa1.vortex.io.VortexReader;
import io.github.dfa1.vortex.scan.Chunk;
import io.github.dfa1.vortex.scan.ScanOptions;
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

    private record ChunkSnapshot(long rowCount, java.util.Set<String> columnNames) {
    }

    /// Materializes every chunk into a value-only snapshot and closes each
    /// chunk before returning, so the result list can outlive the iterator.
    private static List<ChunkSnapshot> snapshotAll(VortexReader vf, ScanOptions opts) {
        var snapshots = new ArrayList<ChunkSnapshot>();
        try (var iter = vf.scan(opts)) {
            iter.forEachRemaining(c ->
                    snapshots.add(new ChunkSnapshot(c.rowCount(), java.util.Set.copyOf(c.columns().keySet()))));
        }
        return snapshots;
    }

    private static EncodingRegistry primitiveRegistry() {
        return EncodingRegistry.builder()
                .register(new AlpEncoding())
                .register(new PrimitiveEncoding())
                .build();
    }

    // ── writeChunk validation ─────────────────────────────────────────────────

    @Test
    void writeChunk_missingColumn_throwsIllegalArgument(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("missing.vtx");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
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
        Path file = tmp.resolve("single.vtx");
        long[] ids = {1L, 2L, 3L};
        double[] vals = {1.0, 2.0, 3.0};

        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults())) {
            // When
            sut.writeChunk(Map.of("id", ids, "value", vals));
        }

        // Then
        var registry = primitiveRegistry();
        try (var vf = VortexReader.open(file, registry)) {
            List<ChunkSnapshot> snapshots = snapshotAll(vf, ScanOptions.all());
            assertThat(snapshots).hasSize(1);
            assertThat(snapshots.getFirst().rowCount()).isEqualTo(3L);
            assertThat(snapshots.getFirst().columnNames()).contains("id", "value");
        }
    }

    @Test
    void writeAndRead_multipleChunks_returnsAllChunks(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("multi.vtx");

        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults())) {
            // When
            sut.writeChunk(Map.of("id", new long[]{1L, 2L}, "value", new double[]{1.0, 2.0}));
            sut.writeChunk(Map.of("id", new long[]{3L, 4L, 5L}, "value", new double[]{3.0, 4.0, 5.0}));
        }

        // Then
        var registry = primitiveRegistry();
        try (var vf = VortexReader.open(file, registry)) {
            List<ChunkSnapshot> snapshots = snapshotAll(vf, ScanOptions.all());
            assertThat(snapshots).hasSize(2);
            assertThat(snapshots.get(0).rowCount()).isEqualTo(2L);
            assertThat(snapshots.get(1).rowCount()).isEqualTo(3L);
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @Test
    void writeAndRead_idValues_decodedCorrectly(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("values.vtx");
        long[] ids = {42L, 100L, -1L};

        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults())) {
            // When
            sut.writeChunk(Map.of("id", ids, "value", new double[]{0.0, 0.0, 0.0}));
        }

        // Then
        var registry = primitiveRegistry();
        var layout = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
        try (var vf = VortexReader.open(file, registry);
             var iter = vf.scan(ScanOptions.all())) {
            assertThat(iter.hasNext()).isTrue();
            try (Chunk chunk = iter.next()) {
                Array idArray = chunk.columns().get("id");
                assertThat(idArray.length()).isEqualTo(3L);
                MemorySegment buf = ArraySegments.of(idArray);
                assertThat(buf.get(layout, 0)).isEqualTo(42L);
                assertThat(buf.get(layout, 8)).isEqualTo(100L);
                assertThat(buf.get(layout, 16)).isEqualTo(-1L);
            }
            assertThat(iter.hasNext()).isFalse();
        }
    }

    @Test
    void scanResult_column_returnsTypedArray(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("typed.vtx");
        long[] ids = {10L, 20L, 30L};

        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults())) {
            sut.writeChunk(Map.of("id", ids, "value", new double[]{1.0, 2.0, 3.0}));
        }

        // When
        var registry = primitiveRegistry();
        try (var vf = VortexReader.open(file, registry);
             var iter = vf.scan(ScanOptions.all())) {
            assertThat(iter.hasNext()).isTrue();
            try (Chunk chunk = iter.next()) {
                LongArray idArray = chunk.column("id");

                // Then
                assertThat(idArray.fold(0L, Long::sum)).isEqualTo(60L);
            }
        }
    }

    @Test
    void scanResult_column_unknownName_throwsVortexException(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("unknown.vtx");

        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults())) {
            sut.writeChunk(Map.of("id", new long[]{1L}, "value", new double[]{1.0}));
        }

        // When / Then
        var registry = primitiveRegistry();
        try (var vf = VortexReader.open(file, registry);
             var iter = vf.scan(ScanOptions.all())) {
            assertThat(iter.hasNext()).isTrue();
            try (Chunk sut = iter.next()) {
                assertThatThrownBy(() -> sut.column("nonexistent"))
                        .hasMessageContaining("unknown column: nonexistent");
            }
        }
    }

    @Test
    void writeAndRead_columnProjection_returnsOnlyRequestedColumns(@TempDir Path tmp)
            throws IOException {
        // Given
        Path file = tmp.resolve("proj.vtx");

        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults())) {
            // When
            sut.writeChunk(Map.of("id", new long[]{1L}, "value", new double[]{9.9}));
        }

        // Then
        var registry = primitiveRegistry();
        try (var vf = VortexReader.open(file, registry)) {
            List<ChunkSnapshot> snapshots = snapshotAll(vf, ScanOptions.columns("id"));
            assertThat(snapshots).hasSize(1);
            assertThat(snapshots.getFirst().columnNames()).contains("id");
            assertThat(snapshots.getFirst().columnNames()).doesNotContain("value");
        }
    }
}
