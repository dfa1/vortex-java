package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.ArraySegments;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.encoding.AlpEncoding;
import io.github.dfa1.vortex.encoding.Registry;
import io.github.dfa1.vortex.encoding.PrimitiveEncoding;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ScanOptions;
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

    private static Registry primitiveRegistry() {
        return Registry.builder()
                .register(new AlpEncoding())
                .register(new PrimitiveEncoding())
                .build();
    }

    // ── writeChunk validation ─────────────────────────────────────────────────

    @Test
    void writeChunk_autoroutesExtensionCollectionViaSpecExtension(@TempDir Path tmp) throws IOException {
        // Given — schema with a vortex.date column; user passes List<LocalDate> directly,
        // expecting the writer to call DateExtension.encodeAll under the hood
        var dateSchema = new DType.Struct(
                List.of("birthdays"),
                List.of(io.github.dfa1.vortex.extension.DateExtension.INSTANCE.dtype(false)),
                false);
        List<java.time.LocalDate> dates = List.of(
                java.time.LocalDate.of(1996, 2, 12),
                java.time.LocalDate.of(2026, 6, 9));
        Path file = tmp.resolve("dates.vtx");

        // When — Collection input replaces the int[] the writer would normally expect.
        // The auto-route resolves the spec extension, encodes to int[], then drops the
        // logical Extension wrapper before picking a physical encoding.
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, dateSchema, WriteOptions.defaults())) {
            sut.writeChunk(Map.of("birthdays", dates));
        }

        // Then — read back through DateExtension.decodeAll and assert end-to-end equality.
        // Registry.loadAll() picks up PrimitiveEncoding (storage) plus DateExtension.
        try (var vf = VortexReader.open(file, Registry.loadAll());
             var iter = vf.scan(ScanOptions.all())) {
            assertThat(iter.hasNext()).isTrue();
            try (Chunk chunk = iter.next()) {
                assertThat(chunk.as("birthdays", java.time.LocalDate.class))
                        .containsExactlyElementsOf(dates);
            }
        }
    }

    @Test
    void writeChunk_roundTripsTimeExtension(@TempDir Path tmp) throws IOException {
        // Given — milliseconds resolution exercises the I32 storage branch of
        // TimeExtension.encodeAll; ns / μs branches go through I64 (not asserted here
        // to keep the test focused — TimeExtension tests cover both).
        DType.Extension timeDtype = io.github.dfa1.vortex.extension.TimeExtension.INSTANCE.dtype(
                io.github.dfa1.vortex.encoding.TimeUnit.Milliseconds, false);
        var schema = new DType.Struct(List.of("clock"), List.of(timeDtype), false);
        List<java.time.LocalTime> times = List.of(
                java.time.LocalTime.of(0, 0, 0, 0),
                java.time.LocalTime.of(1, 1, 1, 500_000_000),
                java.time.LocalTime.of(23, 59, 59, 999_000_000));
        Path file = tmp.resolve("times.vtx");

        // When
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            sut.writeChunk(Map.of("clock", times));
        }

        // Then
        try (var vf = VortexReader.open(file, Registry.loadAll());
             var iter = vf.scan(ScanOptions.all())) {
            assertThat(iter.hasNext()).isTrue();
            try (Chunk chunk = iter.next()) {
                assertThat(chunk.as("clock", java.time.LocalTime.class))
                        .containsExactlyElementsOf(times);
            }
        }
    }

    @Test
    void writeChunk_roundTripsTimestampExtension(@TempDir Path tmp) throws IOException {
        // Given — pre-epoch + epoch + future to exercise sign + boundary; ms resolution
        DType.Extension tsDtype = io.github.dfa1.vortex.extension.TimestampExtension.INSTANCE.dtype(
                io.github.dfa1.vortex.encoding.TimeUnit.Milliseconds, null, false);
        var schema = new DType.Struct(List.of("events"), List.of(tsDtype), false);
        List<java.time.Instant> instants = List.of(
                java.time.Instant.ofEpochMilli(-1_500L),
                java.time.Instant.EPOCH,
                java.time.Instant.ofEpochMilli(1_733_000_000_000L));
        Path file = tmp.resolve("ts.vtx");

        // When
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            sut.writeChunk(Map.of("events", instants));
        }

        // Then
        try (var vf = VortexReader.open(file, Registry.loadAll());
             var iter = vf.scan(ScanOptions.all())) {
            assertThat(iter.hasNext()).isTrue();
            try (Chunk chunk = iter.next()) {
                assertThat(chunk.as("events", java.time.Instant.class))
                        .containsExactlyElementsOf(instants);
            }
        }
    }

    @Test
    void chunkAs_mismatchedDomainType_throws(@TempDir Path tmp) throws IOException {
        // Given — a vortex.date column on disk, but caller asks for Instant
        var dateSchema = new DType.Struct(
                List.of("birthdays"),
                List.of(io.github.dfa1.vortex.extension.DateExtension.INSTANCE.dtype(false)),
                false);
        Path file = tmp.resolve("dates2.vtx");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, dateSchema, WriteOptions.defaults())) {
            sut.writeChunk(Map.of("birthdays",
                    List.of(java.time.LocalDate.of(2026, 6, 10))));
        }

        try (var vf = VortexReader.open(file, Registry.loadAll());
             var iter = vf.scan(ScanOptions.all())) {
            try (Chunk chunk = iter.next()) {
                // When / Then — the accessor must fail-fast, not return a wrongly-cast list
                assertThatThrownBy(() -> chunk.as("birthdays", java.time.Instant.class))
                        .isInstanceOf(io.github.dfa1.vortex.core.VortexException.class)
                        .hasMessageContaining("decodes to LocalDate, not Instant");
            }
        }
    }

    @Test
    void writeChunk_roundTripsUuidExtension(@TempDir Path tmp) throws IOException {
        // Given — UUIDs cover both halves of the 16-byte buffer and a sign-extension edge
        DType.Extension uuidDtype = io.github.dfa1.vortex.extension.UuidExtension.INSTANCE.dtype(false);
        var schema = new DType.Struct(List.of("ids"), List.of(uuidDtype), false);
        List<java.util.UUID> ids = List.of(
                java.util.UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                new java.util.UUID(-1L, -1L),
                new java.util.UUID(0L, 0L));
        Path file = tmp.resolve("uuids.vtx");

        // When
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            sut.writeChunk(Map.of("ids", ids));
        }

        // Then
        try (var vf = VortexReader.open(file, Registry.loadAll());
             var iter = vf.scan(ScanOptions.all())) {
            assertThat(iter.hasNext()).isTrue();
            try (Chunk chunk = iter.next()) {
                assertThat(chunk.as("ids", java.util.UUID.class))
                        .containsExactlyElementsOf(ids);
            }
        }
    }

    @Test
    void writeChunk_cascadeCompressesTimestampExtensionStorage(@TempDir Path tmp) throws IOException {
        // Given — monotonically increasing timestamps that cascade should reduce via
        // FrameOfReference + Bitpacked. Without cascade, storage stays as flat U64.
        DType.Extension tsDtype = io.github.dfa1.vortex.extension.TimestampExtension.INSTANCE.dtype(
                io.github.dfa1.vortex.encoding.TimeUnit.Milliseconds, null, false);
        var schema = new DType.Struct(List.of("events"), List.of(tsDtype), false);
        long base = 1_733_000_000_000L;
        List<java.time.Instant> instants = new ArrayList<>(4096);
        for (int i = 0; i < 4096; i++) {
            instants.add(java.time.Instant.ofEpochMilli(base + i));
        }

        Path flatFile = tmp.resolve("ts_flat.vtx");
        Path cascadedFile = tmp.resolve("ts_cascaded.vtx");

        // When — same data, depth 0 (no cascade) vs depth 3 (cascade enabled)
        writeOne(flatFile, schema, instants, WriteOptions.defaults());
        writeOne(cascadedFile, schema, instants, WriteOptions.cascading(3));

        // Then — cascaded file must be smaller because primitive storage is bit-packed
        long flatSize = java.nio.file.Files.size(flatFile);
        long cascadedSize = java.nio.file.Files.size(cascadedFile);
        assertThat(cascadedSize)
                .as("cascade should compress extension storage; flat=%d cascaded=%d", flatSize, cascadedSize)
                .isLessThan(flatSize);

        // And — cascaded file still round-trips back to the same Instants
        try (var vf = VortexReader.open(cascadedFile, Registry.loadAll());
             var iter = vf.scan(ScanOptions.all())) {
            try (Chunk chunk = iter.next()) {
                assertThat(chunk.as("events", java.time.Instant.class))
                        .containsExactlyElementsOf(instants);
            }
        }
    }

    private static void writeOne(Path file, DType.Struct schema, Object data, WriteOptions options)
            throws IOException {
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, schema, options)) {
            sut.writeChunk(Map.of(schema.fieldNames().get(0), data));
        }
    }

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
