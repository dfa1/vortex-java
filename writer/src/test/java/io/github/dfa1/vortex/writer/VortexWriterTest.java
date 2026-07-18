package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ScanOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VortexWriterTest {

    private static final DType.Struct SCHEMA = new DType.Struct(
            List.of(ColumnName.of("id"), ColumnName.of("value")),
            List.of(DType.I64,
                    DType.F64),
            false);

    private record ChunkSnapshot(long rowCount, java.util.Set<String> columnNames) {
    }

    /// Materializes every chunk into a value-only snapshot and closes each
    /// chunk before returning, so the result list can outlive the iterator.
    private static List<ChunkSnapshot> snapshotAll(VortexReader vf, ScanOptions opts) {
        var snapshots = new ArrayList<ChunkSnapshot>();
        try (var iter = vf.scan(opts)) {
            iter.forEachRemaining(c ->
                    snapshots.add(new ChunkSnapshot(c.rowCount(), c.columns().keySet().stream()
                            .map(io.github.dfa1.vortex.core.model.ColumnName::value)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()))));
        }
        return snapshots;
    }

    private static ReadRegistry primitiveRegistry() {
        return ReadRegistry.builder()
                .register(new io.github.dfa1.vortex.reader.decode.AlpEncodingDecoder())
                .register(new io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder())
                .build();
    }

    @Test
    void writeSegments_are64ByteAligned(@TempDir Path tmp) throws IOException {
        // Given a multi-chunk, multi-column file whose encoded buffers are not 64-byte multiples.
        // VortexWriter pads before each segment so every buffer starts 64-aligned (Arrow-compatible);
        // a broken pad — wrong modulus arithmetic or a skipped writePadding — leaves a segment offset
        // off a 64-byte boundary.
        WriteOptions opts = new WriteOptions(3, false, 0.90, 0, false, false, 256L * 1024 * 1024);
        Path file = tmp.resolve("aligned.vtx");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, opts)) {
            for (int c = 0; c < 3; c++) {
                long[] id = {c * 3L, c * 3L + 1, c * 3L + 2};
                double[] value = {c + 0.5, c + 1.5, c + 2.5};
                sut.writeChunk(Map.of(ColumnName.of("id"), id, ColumnName.of("value"), value));
            }
        }

        // When / Then every data segment starts at a 64-byte boundary
        try (VortexReader reader = VortexReader.open(file)) {
            assertThat(reader.footer().segmentSpecs()).isNotEmpty();
            for (var spec : reader.footer().segmentSpecs()) {
                assertThat(spec.offset() % 64).as("segment offset %d aligned", spec.offset()).isZero();
            }
        }
    }

    @Test
    void create_duplicateFieldNames_throwsIllegalArgumentException(@TempDir Path tmp) throws IOException {
        // Given — a duplicate-name schema built via the DType.Struct record, which validates
        // nothing (only StructBuilder rejects duplicates). The reference writer refuses such
        // schemas ("StructLayout must have unique field names"), so ours must too — otherwise
        // we emit files the canonical implementation would never produce.
        var schema = new DType.Struct(
                List.of(ColumnName.of("dup"), ColumnName.of("dup")),
                List.of(new DType.Primitive(PType.I64, false), new DType.Primitive(PType.I64, false)),
                false);
        Path file = tmp.resolve("dup.vtx");

        // When / Then — options hoisted so only the subject call is in the lambda
        WriteOptions opts = WriteOptions.defaults();
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE)) {
            assertThatThrownBy(() -> VortexWriter.create(ch, schema, opts))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("duplicate field name: dup");
        }
    }

    @Test
    void schema_fieldNameWithNulByte_isUnbuildable() {
        // Given a NUL byte inside a field name: legal in our pure-Java stack, but it aborts the
        // reference toolchain's Arrow FFI export (panic-cannot-unwind in arrow-rs, SIGABRT,
        // measured 2026-07-04). With fieldNames typed as ColumnName the schema can no longer even
        // be constructed, so the footgun is rejected before it can reach the writer.
        // When / Then
        assertThatThrownBy(() -> new DType.Struct(
                List.of(ColumnName.of("col\u0000hidden")),
                List.of(new DType.Primitive(PType.I64, false)),
                false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("U+0000");
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"a\nb", "tab\there"})
    void schema_controlCharFieldName_isUnbuildable(String name) {
        // Given a control-character field name — rejected by ColumnName on both read and write.
        // Blank names are wire-legal and accepted; only control chars break downstream consumers.
        // When / Then
        assertThatThrownBy(() -> new DType.Struct(
                List.of(ColumnName.of(name)),
                List.of(new DType.Primitive(PType.I64, false)),
                false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── writeChunk validation ─────────────────────────────────────────────────

    @Test
    void writeChunk_autoroutesExtensionCollectionViaSpecExtension(@TempDir Path tmp) throws IOException {
        // Given — schema with a vortex.date column; user passes List<LocalDate> directly,
        // expecting the writer to call DateExtension.encodeAll under the hood
        var dateSchema = new DType.Struct(
                List.of(ColumnName.of("birthdays")),
                List.of(io.github.dfa1.vortex.writer.encode.DateExtensionEncoder.INSTANCE.dtype(false)),
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
            sut.writeChunk(Map.of(ColumnName.of("birthdays"), dates));
        }

        // Then — read back through DateExtension.decodeAll and assert end-to-end equality.
        // ReadRegistry.loadAll() picks up PrimitiveEncoding (storage) plus DateExtension.
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll());
             var iter = vf.scan(ScanOptions.all())) {
            assertThat(iter.hasNext()).isTrue();
            try (Chunk chunk = iter.next()) {
                assertThat(chunk.as("birthdays", java.time.LocalDate.class))
                        .containsExactlyElementsOf(dates);
            }
        }
    }

    @Test
    void writeChunk_extensionCollectionColumn_rowCountValidatedAgainstSibling(@TempDir Path tmp)
            throws IOException {
        // Given — a two-column chunk where one column is a List<LocalDate> (extension auto-route)
        // and the sibling is a same-length long[]. The row-count check must measure the collection
        // by its element count: if it reported anything else, the two columns would look mismatched
        // and writeChunk would reject a perfectly valid chunk.
        var schema = new DType.Struct(
                List.of(ColumnName.of("birthdays"), ColumnName.of("id")),
                List.of(io.github.dfa1.vortex.writer.encode.DateExtensionEncoder.INSTANCE.dtype(false),
                        DType.I64),
                false);
        List<java.time.LocalDate> dates = List.of(
                java.time.LocalDate.of(1996, 2, 12),
                java.time.LocalDate.of(2026, 6, 9),
                java.time.LocalDate.of(2030, 1, 1));
        long[] ids = {1L, 2L, 3L};
        Path file = tmp.resolve("ext_rowcount.vtx");

        // When / Then — both columns are 3 rows, so the chunk is accepted and round-trips
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            sut.writeChunk(Map.of(ColumnName.of("birthdays"), dates, ColumnName.of("id"), ids));
        }
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll());
             var iter = vf.scan(ScanOptions.all())) {
            try (Chunk chunk = iter.next()) {
                assertThat(chunk.rowCount()).isEqualTo(3);
                assertThat(chunk.as("birthdays", java.time.LocalDate.class))
                        .containsExactlyElementsOf(dates);
            }
        }
    }

    @Test
    void writeChunk_map_nullablePrimitive_acceptsBoxedArray(@TempDir Path tmp) throws IOException {
        // Given — nullable I64 column passed to the MAP entry point as a boxed Long[] with a null.
        // Regression: the map path used to reject boxed arrays ("unsupported data type: Long[]");
        // only the builder accepted them. Both now share ChunkImpl.validateAndAdapt, so the map
        // form routes the column through nullable → MaskedEncoding. The null round-trip itself is
        // asserted end-to-end (through the JNI reader) by the integration masked test.
        var schema = new DType.Struct(List.of(ColumnName.of("v")),
                List.of(new DType.Primitive(PType.I64, true)), false);
        Path file = tmp.resolve("nullable_map.vtx");

        // When
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            sut.writeChunk(Map.of(ColumnName.of("v"), new Long[]{10L, null, 30L}));
        }

        // Then — the masked file is well-formed
        assertThat(Files.size(file)).isPositive();
    }

    @Test
    void writeChunk_map_nonNullablePrimitive_rejectsBoxedArray(@TempDir Path tmp) throws IOException {
        // Given — a non-nullable I64 column rejects a boxed array on the map path, same as the
        // builder: boxed implies nullability, which the schema does not allow.
        Path file = tmp.resolve("err.vtx");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults())) {
            // When / Then
            Map<ColumnName, Object> boxedColumn = Map.of(ColumnName.of("id"), new Long[]{1L, 2L});
            assertThatThrownBy(() -> sut.writeChunk(boxedColumn))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("non-nullable")
                    .hasMessageContaining("id");
        }
    }

    @Test
    void writeChunk_roundTripsTimeExtension(@TempDir Path tmp) throws IOException {
        // Given — milliseconds resolution exercises the I32 storage branch of
        // TimeExtension.encodeAll; ns / μs branches go through I64 (not asserted here
        // to keep the test focused — TimeExtension tests cover both).
        DType.Extension timeDtype = io.github.dfa1.vortex.writer.encode.TimeExtensionEncoder.INSTANCE.dtype(
                io.github.dfa1.vortex.core.model.TimeUnit.Milliseconds, false);
        var schema = new DType.Struct(List.of(ColumnName.of("clock")), List.of(timeDtype), false);
        List<java.time.LocalTime> times = List.of(
                java.time.LocalTime.of(0, 0, 0, 0),
                java.time.LocalTime.of(1, 1, 1, 500_000_000),
                java.time.LocalTime.of(23, 59, 59, 999_000_000));
        Path file = tmp.resolve("times.vtx");

        // When
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            sut.writeChunk(Map.of(ColumnName.of("clock"), times));
        }

        // Then
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll());
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
        DType.Extension tsDtype = io.github.dfa1.vortex.writer.encode.TimestampExtensionEncoder.INSTANCE.dtype(
                io.github.dfa1.vortex.core.model.TimeUnit.Milliseconds, null, false);
        var schema = new DType.Struct(List.of(ColumnName.of("events")), List.of(tsDtype), false);
        List<java.time.Instant> instants = List.of(
                java.time.Instant.ofEpochMilli(-1_500L),
                java.time.Instant.EPOCH,
                java.time.Instant.ofEpochMilli(1_733_000_000_000L));
        Path file = tmp.resolve("ts.vtx");

        // When
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            sut.writeChunk(Map.of(ColumnName.of("events"), instants));
        }

        // Then
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll());
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
                List.of(ColumnName.of("birthdays")),
                List.of(io.github.dfa1.vortex.writer.encode.DateExtensionEncoder.INSTANCE.dtype(false)),
                false);
        Path file = tmp.resolve("dates2.vtx");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, dateSchema, WriteOptions.defaults())) {
            sut.writeChunk(Map.of(ColumnName.of("birthdays"),
                    List.of(java.time.LocalDate.of(2026, 6, 10))));
        }

        try (var vf = VortexReader.open(file, ReadRegistry.loadAll());
             var iter = vf.scan(ScanOptions.all())) {
            try (Chunk chunk = iter.next()) {
                // When / Then — the accessor must fail-fast, not return a wrongly-cast list
                assertThatThrownBy(() -> chunk.as("birthdays", java.time.Instant.class))
                        .isInstanceOf(io.github.dfa1.vortex.core.error.VortexException.class)
                        .hasMessageContaining("decodes to LocalDate, not Instant");
            }
        }
    }

    @Test
    void writeChunk_roundTripsUuidExtension(@TempDir Path tmp) throws IOException {
        // Given — UUIDs cover both halves of the 16-byte buffer and a sign-extension edge
        DType.Extension uuidDtype = io.github.dfa1.vortex.writer.encode.UuidExtensionEncoder.INSTANCE.dtype(false);
        var schema = new DType.Struct(List.of(ColumnName.of("ids")), List.of(uuidDtype), false);
        List<java.util.UUID> ids = List.of(
                java.util.UUID.fromString("123e4567-e89b-12d3-a456-426614174000"),
                new java.util.UUID(-1L, -1L),
                new java.util.UUID(0L, 0L));
        Path file = tmp.resolve("uuids.vtx");

        // When
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            sut.writeChunk(Map.of(ColumnName.of("ids"), ids));
        }

        // Then
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll());
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
        DType.Extension tsDtype = io.github.dfa1.vortex.writer.encode.TimestampExtensionEncoder.INSTANCE.dtype(
                io.github.dfa1.vortex.core.model.TimeUnit.Milliseconds, null, false);
        var schema = new DType.Struct(List.of(ColumnName.of("events")), List.of(tsDtype), false);
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
        try (var vf = VortexReader.open(cascadedFile, ReadRegistry.loadAll());
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
            Map<ColumnName, Object> partialColumns = Map.of(ColumnName.of("id"), new long[]{1L});
            assertThatThrownBy(() -> sut.writeChunk(partialColumns))
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
            sut.writeChunk(Map.of(ColumnName.of("id"), ids, ColumnName.of("value"), vals));
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
            sut.writeChunk(Map.of(ColumnName.of("id"), new long[]{1L, 2L}, ColumnName.of("value"), new double[]{1.0, 2.0}));
            sut.writeChunk(Map.of(ColumnName.of("id"), new long[]{3L, 4L, 5L}, ColumnName.of("value"), new double[]{3.0, 4.0, 5.0}));
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
            sut.writeChunk(Map.of(ColumnName.of("id"), ids, ColumnName.of("value"), new double[]{0.0, 0.0, 0.0}));
        }

        // Then
        var registry = primitiveRegistry();
        try (var vf = VortexReader.open(file, registry);
             var iter = vf.scan(ScanOptions.all())) {
            assertThat(iter.hasNext()).isTrue();
            try (Chunk chunk = iter.next()) {
                LongArray idArray = chunk.column("id");
                assertThat(idArray.length()).isEqualTo(3L);
                assertThat(idArray.getLong(0)).isEqualTo(42L);
                assertThat(idArray.getLong(1)).isEqualTo(100L);
                assertThat(idArray.getLong(2)).isEqualTo(-1L);
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
            sut.writeChunk(Map.of(ColumnName.of("id"), ids, ColumnName.of("value"), new double[]{1.0, 2.0, 3.0}));
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
            sut.writeChunk(Map.of(ColumnName.of("id"), new long[]{1L}, ColumnName.of("value"), new double[]{1.0}));
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
            sut.writeChunk(Map.of(ColumnName.of("id"), new long[]{1L}, ColumnName.of("value"), new double[]{9.9}));
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

    @Test
    void create_withFullRegistry_roundTrips(@TempDir Path tmp) throws IOException {
        // Given — create(WriteRegistry) over loadAll(), the exact combination that broke on Windows.
        // With no cascade, writeSegment picks the first registered encoder whose accepts() matches
        // the dtype. loadAll() pulls in every service encoder, so this is the end-to-end guard that
        // a deterministic order (WriteRegistry's TreeMap) plus an honest accepts() (composite
        // encoders like ChunkedEncodingEncoder no longer claim raw primitive dtypes) keep selection
        // both stable across platforms and correct.
        var schema = new DType.Struct(List.of(ColumnName.of("v")),
                List.of(DType.I64), false);
        Path file = tmp.resolve("registry.vortex");
        long[] data = {1L, 2L, 3L, 4L};

        // When
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, schema, WriteOptions.defaults(), WriteRegistry.loadAll())) {
            sut.writeChunk(Map.of(ColumnName.of("v"), data));
        }

        // Then
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            assertThat(VortexReads.readAllLongs(vf, "v")).containsExactly(data);
        }
    }
}
