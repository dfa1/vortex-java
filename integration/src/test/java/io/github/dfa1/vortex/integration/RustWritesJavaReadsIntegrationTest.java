package io.github.dfa1.vortex.integration;

import dev.vortex.api.DataSource;
import dev.vortex.api.Expression;
import dev.vortex.api.Partition;
import dev.vortex.api.Scan;
import dev.vortex.api.ScanOptions;
import dev.vortex.api.Session;
import dev.vortex.api.VortexWriter;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.reader.ArrayStats;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.VortexReader;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.Float2Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.SmallIntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.lang.foreign.Arena;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/// Cross-compatibility: Rust (JNI) writer → Java reader.
class RustWritesJavaReadsIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final Schema JNI_SCHEMA = new Schema(List.of(
            Field.notNullable("id", new ArrowType.Int(64, true)),
            Field.notNullable("value", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE))
    ));
    private static final Schema NULLABLE_SCHEMA = new Schema(List.of(
            Field.nullable("id", new ArrowType.Int(64, true))
    ));
    private static final Schema F16_SCHEMA = new Schema(List.of(
            Field.notNullable("v", new ArrowType.FloatingPoint(FloatingPointPrecision.HALF))
    ));

    static {
        NativeLoader.loadJni();
    }

    private static void writeJni(Path file, long[] ids, double[] vals) throws IOException {
        String uri = file.toAbsolutePath().toUri().toString();
        try (VortexWriter writer = VortexWriter.builder(SESSION, uri, JNI_SCHEMA, ALLOCATOR).build()) {
            flushBatch(writer, ids, vals);
        }
    }

    private static void flushBatch(VortexWriter writer, long[] ids, double[] vals) throws IOException {
        int n = ids.length;
        try (VectorSchemaRoot root = VectorSchemaRoot.create(JNI_SCHEMA, ALLOCATOR)) {
            BigIntVector idVec = (BigIntVector) root.getVector("id");
            Float8Vector valVec = (Float8Vector) root.getVector("value");
            idVec.allocateNew(n);
            valVec.allocateNew(n);
            for (int i = 0; i < n; i++) {
                idVec.setSafe(i, ids[i]);
                valVec.setSafe(i, vals[i]);
            }
            root.setRowCount(n);
            try (ArrowArray arr = ArrowArray.allocateNew(ALLOCATOR);
                 ArrowSchema schema = ArrowSchema.allocateNew(ALLOCATOR)) {
                Data.exportVectorSchemaRoot(ALLOCATOR, root, null, arr, schema);
                writer.writeBatch(arr.memoryAddress(), schema.memoryAddress());
            }
        }
    }

    /// Value-only chunk snapshot — copies columnar data out of the per-chunk arena
    /// so assertions can run after the [io.github.dfa1.vortex.reader.Chunk] has been
    /// closed. Each entry in `columns` is a primitive Java array
    /// (long[]/double[]/short[]/…) sized per [PType].
    private record JavaChunk(long rowCount, Map<String, Object> columns) {
    }

    private static List<JavaChunk> scanAll(VortexReader vf) {
        return scanAll(vf, io.github.dfa1.vortex.reader.ScanOptions.all());
    }

    private static List<JavaChunk> scanAll(VortexReader vf,
            io.github.dfa1.vortex.reader.ScanOptions opts) {
        var results = new ArrayList<JavaChunk>();
        try (var iter = vf.scan(opts)) {
            iter.forEachRemaining(c -> {
                var mat = new LinkedHashMap<String, Object>(c.columns().size());
                for (var e : c.columns().entrySet()) {
                    mat.put(e.getKey().value(), snapshotArray(e.getValue().array()));
                }
                results.add(new JavaChunk(c.rowCount(), mat));
            });
        }
        return results;
    }

    /// Copies a primitive [Array]'s underlying [java.lang.foreign.MemorySegment]
    /// into a heap primitive array — long[]/int[]/double[]/float[]/short[]/byte[].
    private static Object snapshotArray(Array arr) {
        var ptype = ((DType.Primitive) arr.dtype()).ptype();
        var seg = arr.materialize(Arena.ofAuto());
        return switch (ptype) {
            case I64, U64 -> seg.toArray(ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN));
            case I32, U32 -> seg.toArray(ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN));
            case F64 -> seg.toArray(ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN));
            case F32 -> seg.toArray(ValueLayout.JAVA_FLOAT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN));
            case F16, I16, U16 -> seg.toArray(ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN));
            case I8, U8 -> seg.toArray(ValueLayout.JAVA_BYTE);
        };
    }

    // ── JNI write helpers ─────────────────────────────────────────────────────

    private static long[] ids(JavaChunk chunk) {
        return (long[]) chunk.columns().get("id");
    }

    private static double[] values(JavaChunk chunk) {
        return (double[]) chunk.columns().get("value");
    }

    // ── Java read helpers ─────────────────────────────────────────────────────

    private static String firstI64Column(Path file) throws IOException {
        try (var vf = VortexReader.open(file, ReadRegistry.empty())) {
            if (vf.dtype() instanceof DType.Struct struct) {
                for (int i = 0; i < struct.fieldNames().size(); i++) {
                    if (struct.fieldTypes().get(i) instanceof DType.Primitive(PType pt, _) && pt == PType.I64) {
                        return struct.fieldNames().get(i).value();
                    }
                }
            }
            throw new AssertionError("no I64 column found in " + file.getFileName());
        }
    }

    private static long[] readJniLongColumn(Path file, String column) throws IOException {
        ScanOptions opts = ScanOptions.builder()
                                   .projection(Expression.select(new String[]{column}, Expression.root()))
                                   .build();
        var longs = new ArrayList<Long>();
        forEachArrowBatch(file, opts, root -> {
            BigIntVector vec = (BigIntVector) root.getVector(column);
            for (int i = 0; i < root.getRowCount(); i++) {
                longs.add(vec.get(i));
            }
        });
        return longs.stream().mapToLong(Long::longValue).toArray();
    }

    /// Scans `file` through the JNI Arrow reader and hands every loaded
    /// [VectorSchemaRoot] batch to `batch`. Centralizes the
    /// open → scan → partition → loadNextBatch boilerplate shared by the
    /// JNI-reader assertions.
    private static void forEachArrowBatch(Path file, ScanOptions opts, Consumer<VectorSchemaRoot> batch)
            throws IOException {
        String uri = file.toAbsolutePath().toUri().toString();
        DataSource ds = DataSource.open(SESSION, uri);
        Scan scan = ds.scan(opts);
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
                while (reader.loadNextBatch()) {
                    batch.accept(reader.getVectorSchemaRoot());
                }
            }
        }
    }

    private static long[] readJavaLongColumn(Path file, String column) throws IOException {
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll());
             var iter = vf.scan(io.github.dfa1.vortex.reader.ScanOptions.columns(ColumnName.of(column)))) {
            var longs = new ArrayList<Long>();
            iter.forEachRemaining(c -> {
                LongArray arr = c.column(ColumnName.of(column));
                for (long i = 0; i < arr.length(); i++) {
                    longs.add(arr.getLong(i));
                }
            });
            return longs.stream().mapToLong(Long::longValue).toArray();
        }
    }

    // ── S3 fixture round-trip: Rust-written pco → Java reader ────────────────

    @Test
    void jniWriter_javaReader_singleChunk(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("jni_single.vtx");
        long[] ids = {1L, 2L, 3L};
        double[] vals = {1.1, 2.2, 3.3};
        writeJni(file, ids, vals);

        // When / Then
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            List<JavaChunk> results = scanAll(vf);
            assertThat(results).hasSize(1);
            assertThat(results.getFirst().rowCount()).isEqualTo(3L);
            assertThat(ids(results.getFirst())).containsExactly(1L, 2L, 3L);
            assertThat(values(results.getFirst())).containsExactly(1.1, 2.2, 3.3);
        }
    }

    @Test
    void jniWriter_columnZones_tileTheColumnAtTheDeclaredStride(@TempDir Path tmp) throws IOException {
        // Given — Rust's vortex.zoned declares a uniform zone length independent of chunk
        // boundaries; the zones must tile [0, n) contiguously, each describing exactly its rows
        // (ids equal row numbers, so a zone's min/max must be its own first/last row).
        int n = 200_000;
        long[] ids = new long[n];
        double[] vals = new double[n];
        for (int i = 0; i < n; i++) {
            ids[i] = i;
            vals[i] = i;
        }
        Path file = tmp.resolve("jni_zone_ranges.vtx");
        writeJni(file, ids, vals);

        // When
        List<io.github.dfa1.vortex.reader.Zone> result;
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll());
             var iter = vf.scan(io.github.dfa1.vortex.reader.ScanOptions.all())) {
            result = iter.columnZones(ColumnName.of("id"));
        }

        // Then
        assertThat(result).hasSizeGreaterThan(1);
        long next = 0;
        for (var zone : result) {
            assertThat(zone.firstRow()).isEqualTo(next);
            assertThat(zone.stats().min()).isEqualTo(zone.firstRow());
            assertThat(zone.stats().max()).isEqualTo(zone.firstRow() + zone.rowCount() - 1);
            next += zone.rowCount();
        }
        assertThat(next).isEqualTo(n);
    }

    @Test
    void jniWriter_filteredUtf8Scan_prunesOnBoundedZoneStats(@TempDir Path tmp) throws IOException {
        // Given — Rust records string zones as vortex.bounded_max/bounded_min (#446). Sorted keys
        // spanning several ~1 MB chunks, so only the chunk holding the key can match; before
        // bounded stats were read, the zone map was dropped and every chunk was decoded.
        int n = 1_000_000;
        String[] keys = new String[n];
        for (int i = 0; i < n; i++) {
            keys[i] = String.format("key-%08d", i);
        }
        Path file = tmp.resolve("jni_utf8_pruning.vtx");
        writeJniUtf8(file, keys);
        long totalChunks = countChunks(file, io.github.dfa1.vortex.reader.ScanOptions.all());

        // When
        long result = countChunks(file, io.github.dfa1.vortex.reader.ScanOptions.all()
                .withFilter(io.github.dfa1.vortex.reader.RowFilter.eq(ColumnName.of("k"), "key-00000005")));

        // Then
        assertThat(totalChunks).isGreaterThan(1);
        assertThat(result).isEqualTo(1);
    }

    @Test
    void jniWriter_filteredUtf8Scan_comparesInUtf8ByteOrder(@TempDir Path tmp) throws IOException {
        // Given — Rust writes string zone stats in UTF-8 byte order: min U+E000, max the emoji. The
        // reader compared filters in UTF-16 order, where the emoji (a surrogate pair) sorts below
        // U+E000, so eq(emoji) "proved" the zone empty and dropped the matching row.
        String emoji = "\uD83D\uDE00";
        Path file = tmp.resolve("jni_utf8_order.vtx");
        writeJniUtf8(file, new String[]{"\uE000", emoji});

        // When
        long result = countChunks(file, io.github.dfa1.vortex.reader.ScanOptions.all()
                .withFilter(io.github.dfa1.vortex.reader.RowFilter.eq(ColumnName.of("k"), emoji)));

        // Then — the only chunk holds the match, so it must survive pruning
        assertThat(result).isEqualTo(1);
    }

    private static long countChunks(Path file, io.github.dfa1.vortex.reader.ScanOptions opts) throws IOException {
        long[] count = {0};
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll());
             var iter = vf.scan(opts)) {
            iter.forEachRemaining(c -> count[0]++);
        }
        return count[0];
    }

    private static void writeJniUtf8(Path file, String[] values) throws IOException {
        Schema schema = new Schema(List.of(Field.notNullable("k", new ArrowType.Utf8())));
        String uri = file.toAbsolutePath().toUri().toString();
        try (VortexWriter writer = VortexWriter.builder(SESSION, uri, schema, ALLOCATOR).build();
             VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
            VarCharVector vec = (VarCharVector) root.getVector("k");
            vec.allocateNew(values.length);
            for (int i = 0; i < values.length; i++) {
                vec.setSafe(i, values[i].getBytes(java.nio.charset.StandardCharsets.UTF_8));
            }
            root.setRowCount(values.length);
            try (ArrowArray arr = ArrowArray.allocateNew(ALLOCATOR);
                 ArrowSchema arrowSchema = ArrowSchema.allocateNew(ALLOCATOR)) {
                Data.exportVectorSchemaRoot(ALLOCATOR, root, null, arr, arrowSchema);
                writer.writeBatch(arr.memoryAddress(), arrowSchema.memoryAddress());
            }
        }
    }

    @Test
    void jniWriter_noPerZoneSum_zoneReducerSignalsFallbackAndDecodeStillCorrect(@TempDir Path tmp)
            throws IOException {
        // Given — a Rust-written file large enough that the JNI writer emits a multi-zone column.
        // vortex-jni 0.85.0 dropped SUM from the zone-map aggregate defaults (a zone sum prunes
        // nothing, and its null-on-empty semantics were unsettled — see spiraldb/vortex#9206's
        // writer comment), so zones now carry only MAX/MIN/NAN_COUNT/NULL_COUNT. This proves the
        // Java reader reflects that absence faithfully (no fabricated SUM), that
        // [io.github.dfa1.vortex.reader.compute.ZoneReducer#sum(String)] signals the caller to fall
        // back instead of under-counting, and that decoding the column directly still yields the
        // right total.
        int n = 200_000;
        long[] ids = new long[n];
        double[] vals = new double[n];
        for (int i = 0; i < n; i++) {
            ids[i] = i;
            vals[i] = i;
        }
        Path file = tmp.resolve("jni_zones.vtx");
        writeJni(file, ids, vals);
        long expected = (long) n * (n - 1) / 2; // Σ 0..n-1

        // When / Then — no zone carries a SUM statistic anymore
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll());
             var iter = vf.scan(io.github.dfa1.vortex.reader.ScanOptions.all())) {
            List<ArrayStats> zones = iter.columnZoneStats(ColumnName.of("id"));
            assertThat(zones).isNotEmpty().allSatisfy(z -> assertThat(z.sum()).isNull());
        }

        // When / Then — the reducer refuses to guess and tells the caller to stream instead
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            Number pushedDown = new io.github.dfa1.vortex.reader.compute.ZoneReducer(vf).sum(ColumnName.of("id"));
            assertThat(pushedDown).isNull();
        }

        // When / Then — decoding the column the ordinary way still returns the exact total
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            long total = scanAll(vf).stream().flatMapToLong(c -> Arrays.stream(ids(c))).sum();
            assertThat(total).isEqualTo(expected);
        }
    }

    @Test
    void jniWriter_javaReader_multipleChunks(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("jni_multi.vtx");
        String uri = file.toAbsolutePath().toUri().toString();
        try (VortexWriter writer = VortexWriter.builder(SESSION, uri, JNI_SCHEMA, ALLOCATOR).build()) {
            flushBatch(writer, new long[]{1L, 2L}, new double[]{1.1, 2.2});
            flushBatch(writer, new long[]{3L, 4L, 5L}, new double[]{3.3, 4.4, 5.5});
        }

        // When / Then — JNI may merge small batches; verify total rows and values
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            List<JavaChunk> results = scanAll(vf);
            long totalRows = results.stream().mapToLong(JavaChunk::rowCount).sum();
            assertThat(totalRows).isEqualTo(5L);
            long[] allIds = results.stream()
                                    .flatMapToLong(r -> java.util.Arrays.stream(ids(r)))
                                    .toArray();
            assertThat(allIds).containsExactly(1L, 2L, 3L, 4L, 5L);
        }
    }

    @Test
    void jniWriter_javaReader_firstTenRows_matchesJniRead(@TempDir Path tmp) throws IOException {
        // Given — JNI writes 1_000 rows; both readers consume only the first 10
        Path file = tmp.resolve("jni_first_ten.vtx");
        int n = 1_000;
        long[] ids = new long[n];
        double[] vals = new double[n];
        for (int i = 0; i < n; i++) {
            ids[i] = i + 1L;
            vals[i] = i * 0.5;
        }
        writeJni(file, ids, vals);

        // When — Java reader with ScanOptions.limit(10)
        long[] javaIds;
        double[] javaVals;
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll());
             var iter = vf.scan(io.github.dfa1.vortex.reader.ScanOptions.all().withLimit(10))) {
            long rowsSeen = 0;
            var idList = new ArrayList<Long>();
            var valList = new ArrayList<Double>();
            while (iter.hasNext() && rowsSeen < 10) {
                try (var c = iter.next()) {
                    LongArray idCol = c.column(ColumnName.of("id"));
                    DoubleArray valCol = c.column(ColumnName.of("value"));
                    long take = Math.min(idCol.length(), 10 - rowsSeen);
                    for (long i = 0; i < take; i++) {
                        idList.add(idCol.getLong(i));
                        valList.add(valCol.getDouble(i));
                    }
                    rowsSeen += take;
                }
            }
            javaIds = idList.stream().mapToLong(Long::longValue).toArray();
            javaVals = valList.stream().mapToDouble(Double::doubleValue).toArray();
        }

        // When — JNI reader stops after 10 rows
        var jniIdList = new ArrayList<Long>();
        var jniValList = new ArrayList<Double>();
        forEachArrowBatch(file, ScanOptions.of(), root -> {
            if (jniIdList.size() >= 10) {
                return;
            }
            BigIntVector idVec = (BigIntVector) root.getVector("id");
            Float8Vector valVec = (Float8Vector) root.getVector("value");
            for (int i = 0; i < root.getRowCount() && jniIdList.size() < 10; i++) {
                jniIdList.add(idVec.get(i));
                jniValList.add(valVec.get(i));
            }
        });
        long[] jniIds = jniIdList.stream().mapToLong(Long::longValue).toArray();
        double[] jniVals = jniValList.stream().mapToDouble(Double::doubleValue).toArray();

        // Then — both readers agree element-wise on the first 10 rows
        assertThat(javaIds).hasSize(10).containsExactly(jniIds);
        assertThat(javaVals).hasSize(10).containsExactly(jniVals);
        assertThat(javaIds).containsExactly(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
    }

    @Test
    void jniWriter_javaReader_columnProjection(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("jni_proj.vtx");
        writeJni(file, new long[]{10L, 20L}, new double[]{0.1, 0.2});

        // When / Then
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            List<JavaChunk> results = scanAll(vf, io.github.dfa1.vortex.reader.ScanOptions.columns(ColumnName.of("id")));
            assertThat(results).hasSize(1);
            assertThat(results.getFirst().columns()).containsKey("id");
            assertThat(results.getFirst().columns()).doesNotContainKey("value");
            assertThat(ids(results.getFirst())).containsExactly(10L, 20L);
        }
    }

    @Test
    void jniWriter_javaReader_fewUniqueF64Values(@TempDir Path tmp) throws IOException {
        // Given — 10_000 rows cycling through only 3 unique F64 values to trigger dict encoding
        int n = 10_000;
        long[] ids = new long[n];
        double[] vals = new double[n];
        double[] unique = {1.1, 2.2, 3.3};
        for (int i = 0; i < n; i++) {
            ids[i] = i;
            vals[i] = unique[i % unique.length];
        }
        Path file = tmp.resolve("jni_dict.vtx");
        writeJni(file, ids, vals);

        // When / Then
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            List<JavaChunk> results = scanAll(vf, io.github.dfa1.vortex.reader.ScanOptions.columns(ColumnName.of("value")));
            long total = results.stream().mapToLong(JavaChunk::rowCount).sum();
            assertThat(total).isEqualTo(n);
            double sum = 0;
            double[] first9 = new double[9];
            int spotIdx = 0;
            for (JavaChunk r : results) {
                double[] decoded = values(r);
                for (double v : decoded) {
                    sum += v;
                    if (spotIdx < 9) {
                        first9[spotIdx++] = v;
                    }
                }
            }
            // 10_000 rows: 3333 full cycles of [1.1,2.2,3.3] (=6.6 each) + one 1.1 remainder
            assertThat(sum).isCloseTo(21_998.9, org.assertj.core.data.Offset.offset(0.1));
            // spot-check: first 9 values must cycle [1.1,2.2,3.3] — catches silent value corruption
            // that leaves sums intact but permutes elements (e.g. proto tag drift on dict encoding)
            assertThat(first9).containsExactly(1.1, 2.2, 3.3, 1.1, 2.2, 3.3, 1.1, 2.2, 3.3);
        }
    }

    /// Decimal shapes the Java reader rejected though Rust writes them: a precision-2 column (Rust
    /// stores it as i8, whose proto3 metadata is empty: "missing metadata"), a precision above 38
    /// (i256; the reader capped precision at 38) and a negative scale (legal in Rust, rejected too).
    @ParameterizedTest(name = "decimal({0},{1}) bitWidth={2}")
    @CsvSource({"2, 0, 128", "50, 5, 256", "10, -2, 128"})
    void jniWriter_javaReader_decimalBounds(int precision, int scale, int bitWidth, @TempDir Path tmp)
            throws IOException {
        // Given — seeded values spanning the precision, including its extremes
        Schema schema = new Schema(List.of(
                Field.notNullable("v", new ArrowType.Decimal(precision, scale, bitWidth))));
        java.util.Random random = new java.util.Random(precision);
        java.math.BigInteger max = java.math.BigInteger.TEN.pow(precision).subtract(java.math.BigInteger.ONE);
        BigDecimal[] expected = new BigDecimal[500];
        for (int i = 0; i < expected.length; i++) {
            java.math.BigInteger unscaled = switch (i) {
                case 0 -> max;
                case 1 -> max.negate();
                default -> new java.math.BigInteger(max.bitLength(), random).mod(max)
                        .multiply(random.nextBoolean() ? java.math.BigInteger.ONE : java.math.BigInteger.ONE.negate());
            };
            expected[i] = new BigDecimal(unscaled, scale);
        }
        Path file = tmp.resolve("jni_decimal.vtx");
        String uri = file.toAbsolutePath().toUri().toString();
        try (VortexWriter writer = VortexWriter.builder(SESSION, uri, schema, ALLOCATOR).build();
             VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
            var vec = root.getVector("v");
            vec.setInitialCapacity(expected.length);
            vec.allocateNew();
            for (int i = 0; i < expected.length; i++) {
                if (vec instanceof org.apache.arrow.vector.DecimalVector d) {
                    d.setSafe(i, expected[i]);
                } else {
                    ((org.apache.arrow.vector.Decimal256Vector) vec).setSafe(i, expected[i]);
                }
            }
            root.setRowCount(expected.length);
            try (ArrowArray arr = ArrowArray.allocateNew(ALLOCATOR);
                 ArrowSchema arrowSchema = ArrowSchema.allocateNew(ALLOCATOR)) {
                Data.exportVectorSchemaRoot(ALLOCATOR, root, null, arr, arrowSchema);
                writer.writeBatch(arr.memoryAddress(), arrowSchema.memoryAddress());
            }
        }

        // When
        var result = new ArrayList<BigDecimal>();
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll());
             var iter = vf.scan(io.github.dfa1.vortex.reader.ScanOptions.all())) {
            iter.forEachRemaining(c -> {
                io.github.dfa1.vortex.reader.array.DecimalArray arr = c.column(ColumnName.of("v"));
                for (long i = 0; i < arr.length(); i++) {
                    result.add(arr.getDecimal(i));
                }
            });
        }

        // Then
        assertThat(result).containsExactly(expected);
    }

    private static final Schema I16_SCHEMA = new Schema(List.of(
            Field.notNullable("v", new ArrowType.Int(16, true))
    ));

    private static void writeJniI16(Path file, short[] vals) throws IOException {
        String uri = file.toAbsolutePath().toUri().toString();
        try (VortexWriter writer = VortexWriter.builder(SESSION, uri, I16_SCHEMA, ALLOCATOR).build();
             VectorSchemaRoot root = VectorSchemaRoot.create(I16_SCHEMA, ALLOCATOR)) {
            SmallIntVector vec = (SmallIntVector) root.getVector("v");
            vec.allocateNew(vals.length);
            for (int i = 0; i < vals.length; i++) {
                vec.setSafe(i, vals[i]);
            }
            root.setRowCount(vals.length);
            try (ArrowArray arr = ArrowArray.allocateNew(ALLOCATOR);
                 ArrowSchema schema = ArrowSchema.allocateNew(ALLOCATOR)) {
                Data.exportVectorSchemaRoot(ALLOCATOR, root, null, arr, schema);
                writer.writeBatch(arr.memoryAddress(), schema.memoryAddress());
            }
        }
    }

    @Test
    void jniWriter_javaReader_lowCardinalityI16(@TempDir Path tmp) throws IOException {
        // Given — 10_000 rows cycling 3 unique I16 values. Ground-truth cross-check: does the
        // Rust/JNI compressor dict-encode a low-cardinality I16 column, and can the Java reader
        // read it back? (The Java writer's global dict admitted I16 but the Java reader rejected
        // it with "unsupported ptype for lazy dict: I16".) If Rust dicts I16 and Java cannot read
        // it, the bug is reader-side and this test fails with that exact message.
        int n = 10_000;
        short[] unique = {7, 8, 9};
        short[] vals = new short[n];
        for (int i = 0; i < n; i++) {
            vals[i] = unique[i % unique.length];
        }
        Path file = tmp.resolve("jni_i16_lowcard.vtx");
        writeJniI16(file, vals);

        // When / Then — must round-trip exactly
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            List<JavaChunk> results = scanAll(vf, io.github.dfa1.vortex.reader.ScanOptions.columns(ColumnName.of("v")));
            long total = results.stream().mapToLong(JavaChunk::rowCount).sum();
            assertThat(total).isEqualTo(n);
            var got = new ArrayList<Short>();
            for (JavaChunk r : results) {
                short[] decoded = (short[]) r.columns().get("v");
                for (short s : decoded) {
                    got.add(s);
                }
            }
            for (int i = 0; i < n; i++) {
                assertThat(got.get(i)).as("row %d", i).isEqualTo(unique[i % unique.length]);
            }
        }
    }

    // ── S3 helpers ────────────────────────────────────────────────────────────

    @Test
    void jniWriter_nullableColumn_decodesWithoutError(@TempDir Path tmp) throws IOException {
        // Given — nullable I64 column; the JNI compressor encodes this as fastlanes.bitpacked
        // (not vortex.masked) since the compressor folds validity into the encoding.
        // This test verifies end-to-end decoding of nullable schemas does not throw.
        Path file = tmp.resolve("nullable.vtx");
        String uri = file.toAbsolutePath().toUri().toString();
        int n = 10_000;
        try (VortexWriter writer = VortexWriter.builder(SESSION, uri, NULLABLE_SCHEMA, ALLOCATOR).build()) {
            try (VectorSchemaRoot root = VectorSchemaRoot.create(NULLABLE_SCHEMA, ALLOCATOR)) {
                BigIntVector idVec = (BigIntVector) root.getVector("id");
                idVec.allocateNew(n);
                for (int i = 0; i < n; i++) {
                    if (i % 5 == 0) {
                        idVec.setNull(i);
                    } else {
                        idVec.setSafe(i, i);
                    }
                }
                root.setRowCount(n);
                try (ArrowArray arr = ArrowArray.allocateNew(ALLOCATOR);
                     ArrowSchema schema = ArrowSchema.allocateNew(ALLOCATOR)) {
                    Data.exportVectorSchemaRoot(ALLOCATOR, root, null, arr, schema);
                    writer.writeBatch(arr.memoryAddress(), schema.memoryAddress());
                }
            }
        }

        // When / Then — decodes without error, correct row count, correct values
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            List<JavaChunk> results = scanAll(vf);
            long totalRows = results.stream().mapToLong(JavaChunk::rowCount).sum();
            assertThat(totalRows).isEqualTo(n);
            assertThat(vf.dtype()).isInstanceOf(DType.Struct.class);
            DType colDtype = ((DType.Struct) vf.dtype()).field(ColumnName.of("id"));
            assertThat(colDtype.nullable()).isTrue();
            // null slots store 0 (bitpacked folds validity); non-null slot i stores i
            // sum(i for i in [0,10000) if i%5!=0) = 49995000 - 5*(0+5+…+9995) = 40000000
            // if proto tag drifts, all values decode as 0 and sum would be 0
            long sum = 0;
            for (JavaChunk r : results) {
                for (long v : ids(r)) {
                    sum += v;
                }
            }
            assertThat(sum).isEqualTo(40_000_000L);
        }
    }

    @Tag("slow") // S3 download, 1.5-6 s per case
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {
        "pco.vortex",                        // all pco ptypes: Classic+Consecutive+IntMult+FloatMult
        "tpch_lineitem.compact.vortex",      // I64/I32/Decimal/date: Classic+Consecutive
        "tpch_orders.compact.vortex",        // I64/I32/Decimal/date: Classic+Consecutive
        "clickbench_hits_5k.compact.vortex"  // I16/I32/I64/ts-I64: Classic+Consecutive
    })
    void s3_javaDecodeMatchesJni(String fixture, @TempDir Path tmp) throws Exception {
        // Given — Java decode of a first-I64 column from an S3 fixture
        RustFixtures.assumeNetworkAvailable();
        Path file = RustFixtures.downloadArray(tmp, fixture);
        String col = firstI64Column(file);

        // When
        long[] jni = readJniLongColumn(file, col);
        long[] java = readJavaLongColumn(file, col);

        // Then — same values element-wise (chunk order may differ)
        Arrays.sort(jni);
        Arrays.sort(java);
        assertThat(java).containsExactly(jni);
    }

    @Tag("slow") // S3 download
    @ParameterizedTest(name = "{0}")
    @CsvSource({
        "tpch_lineitem.regular.vortex, l_extendedprice",
        "tpch_lineitem.compact.vortex, l_extendedprice",
        "tpch_orders.compact.vortex,   o_totalprice",
        "tpch_orders.regular.vortex,   o_totalprice"
    })
    void s3_fullScan_decimalColumnMatchesJni(String fixture, String decimalColumn, @TempDir Path tmp)
            throws Exception {
        // Given — a full scan (every column, unlike the single-column test above) of a TPC-H fixture
        // whose columns are chunked differently, so the scan plans windows narrower than some
        // column's chunk and slices that chunk per window. Slicing had no case for decimal arrays,
        // so the scan threw "cannot slice shared array of type LazyDecimalBytePartsArray" and none
        // of the TPC-H fixtures could be read.
        RustFixtures.assumeNetworkAvailable();
        Path file = RustFixtures.downloadArray(tmp, fixture);
        var jni = new ArrayList<BigDecimal>();
        forEachArrowBatch(file, ScanOptions.of(), root -> {
            var vec = root.getVector(decimalColumn);
            for (int i = 0; i < root.getRowCount(); i++) {
                jni.add((BigDecimal) vec.getObject(i));
            }
        });

        // When
        var result = new ArrayList<BigDecimal>();
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll());
             var iter = vf.scan(io.github.dfa1.vortex.reader.ScanOptions.all())) {
            iter.forEachRemaining(c -> {
                io.github.dfa1.vortex.reader.array.DecimalArray arr = c.column(ColumnName.of(decimalColumn));
                for (long i = 0; i < arr.length(); i++) {
                    result.add(arr.getDecimal(i));
                }
            });
        }

        // Then — same values (vortex-jni may return partitions in a different order)
        assertThat(result).hasSize(jni.size()).containsExactlyInAnyOrderElementsOf(jni);
    }

    @Tag("slow") // S3 download
    @Test
    void s3_csvExport_decimalColumnMatchesJni(@TempDir Path tmp) throws Exception {
        // Given — a Rust-written TPC-H fixture with a decimal(15, 2) column. CsvExporter had no case
        // for decimal arrays and threw "unsupported array type for CSV export" on any decimal column
        // (found on Raincloud's tpcgen-rs-tpch-sf1-* datasets). The public writer cannot produce
        // decimal columns, so a Rust-written file is the only way to exercise this.
        RustFixtures.assumeNetworkAvailable();
        Path file = RustFixtures.downloadArray(tmp, "tpch_orders.compact.vortex");
        var jni = new ArrayList<String>();
        forEachArrowBatch(file, ScanOptions.of(), root -> {
            var vec = root.getVector("o_totalprice");
            for (int i = 0; i < root.getRowCount(); i++) {
                jni.add(((BigDecimal) vec.getObject(i)).toPlainString());
            }
        });
        Path csv = tmp.resolve("orders.csv");

        // When
        io.github.dfa1.vortex.csv.CsvExporter.exportCsv(file, csv);

        // Then — same plain-notation values (vortex-jni may return partitions in a different order)
        var result = new ArrayList<String>();
        try (var reader = de.siegmar.fastcsv.reader.CsvReader.builder().ofCsvRecord(csv)) {
            var rows = reader.iterator();
            int column = rows.next().getFields().indexOf("o_totalprice");
            rows.forEachRemaining(row -> result.add(row.getField(column)));
        }
        assertThat(result).hasSize(jni.size()).containsExactlyInAnyOrderElementsOf(jni);
    }

    @Tag("slow") // S3 download
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"tpch_orders.regular.vortex", "clickbench_hits_5k.regular.vortex"})
    void s3_fullScan_onPairUtf8ColumnsMatchJni(String fixture, @TempDir Path tmp) throws Exception {
        // Given — the two v0.86.1 fixtures whose string columns Rust compressed with vortex.onpair
        // (edition core2026.08.1); before #425 the Java scan failed with "no decoder registered". Every
        // Utf8 column is compared, so the OnPair ones are covered whichever columns they are.
        RustFixtures.assumeNetworkAvailable();
        Path file = RustFixtures.downloadArray(tmp, fixture);
        var jni = new LinkedHashMap<String, List<String>>();
        forEachArrowBatch(file, ScanOptions.of(), root -> {
            for (var vec : root.getFieldVectors()) {
                ArrowType.ArrowTypeID type = vec.getField().getType().getTypeID();
                if (type == ArrowType.ArrowTypeID.Utf8 || type == ArrowType.ArrowTypeID.Utf8View) {
                    var col = jni.computeIfAbsent(vec.getName(), k -> new ArrayList<>());
                    for (int i = 0; i < root.getRowCount(); i++) {
                        Object v = vec.getObject(i);
                        col.add(v == null ? null : v.toString());
                    }
                }
            }
        });

        // When
        var result = new LinkedHashMap<String, List<String>>();
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll());
             var iter = vf.scan(io.github.dfa1.vortex.reader.ScanOptions.all())) {
            iter.forEachRemaining(c -> {
                for (String name : jni.keySet()) {
                    Array arr = c.column(ColumnName.of(name));
                    var col = result.computeIfAbsent(name, k -> new ArrayList<>());
                    for (long i = 0; i < arr.length(); i++) {
                        col.add(arr instanceof MaskedArray m
                                ? (m.isValid(i) ? ((VarBinArray) m.inner()).getString(i) : null)
                                : ((VarBinArray) arr).getString(i));
                    }
                }
            });
        }

        // Then — same values per column (vortex-jni may return partitions in a different order)
        assertThat(jni).isNotEmpty();
        assertThat(result).containsOnlyKeys(jni.keySet());
        jni.forEach((name, expected) ->
                assertThat(result.get(name)).as(name).containsExactlyInAnyOrderElementsOf(expected));
    }

    @Test
    void jniWriter_javaReader_f16_primitiveRoundTrip(@TempDir Path tmp) throws IOException {
        // Given — four F16 values; JNI Rust compressor encodes as vortex.flat/PrimitiveEncoding
        // This is the cross-compatibility check: Rust writes F16, Java reads it back bit-exact
        Path file = tmp.resolve("f16_roundtrip.vtx");
        short[] f16bits = {
            Float.floatToFloat16(0.0f),
            Float.floatToFloat16(1.0f),
            Float.floatToFloat16(2.0f),
            Float.floatToFloat16(3.0f),
        };
        String uri = file.toAbsolutePath().toUri().toString();
        try (VortexWriter writer = VortexWriter.builder(SESSION, uri, F16_SCHEMA, ALLOCATOR).build()) {
            try (VectorSchemaRoot root = VectorSchemaRoot.create(F16_SCHEMA, ALLOCATOR)) {
                Float2Vector vec = (Float2Vector) root.getVector("v");
                vec.allocateNew(f16bits.length);
                for (int i = 0; i < f16bits.length; i++) {
                    vec.setSafe(i, f16bits[i]);
                }
                root.setRowCount(f16bits.length);
                try (ArrowArray arr = ArrowArray.allocateNew(ALLOCATOR);
                     ArrowSchema schema = ArrowSchema.allocateNew(ALLOCATOR)) {
                    Data.exportVectorSchemaRoot(ALLOCATOR, root, null, arr, schema);
                    writer.writeBatch(arr.memoryAddress(), schema.memoryAddress());
                }
            }
        }

        // When
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            List<JavaChunk> results = scanAll(vf);

            // Then — correct dtype, correct values
            assertThat(vf.dtype()).isInstanceOf(DType.Struct.class);
            assertThat(((DType.Struct) vf.dtype()).field(ColumnName.of("v")))
                    .isEqualTo(DType.F16);
            assertThat(results).hasSize(1);
            // F16 column snapshots as a short[] (raw float16 bits)
            short[] decoded = (short[]) results.getFirst().columns().get("v");
            assertThat(decoded).hasSameSizeAs(f16bits);
            for (int i = 0; i < f16bits.length; i++) {
                assertThat(Float.float16ToFloat(decoded[i]))
                        .as("index %d", i)
                        .isEqualTo(Float.float16ToFloat(f16bits[i]));
            }
        }
    }
}
