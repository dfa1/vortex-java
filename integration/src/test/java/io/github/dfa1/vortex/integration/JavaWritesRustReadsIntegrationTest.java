package io.github.dfa1.vortex.integration;

import dev.vortex.api.DataSource;
import dev.vortex.api.Expression;
import dev.vortex.api.Partition;
import dev.vortex.api.Scan;
import dev.vortex.api.ScanOptions;
import dev.vortex.api.Session;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.core.testing.OhlcData;
import io.github.dfa1.vortex.writer.encode.BoolEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.PcoEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.ByteBoolEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.ConstantEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.DateExtensionEncoder;
import io.github.dfa1.vortex.writer.encode.DeltaEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.FsstEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.ListData;
import io.github.dfa1.vortex.writer.encode.ListEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.ListViewData;
import io.github.dfa1.vortex.writer.encode.ListViewEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.NullEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.RleEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.RunEndEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.SparseEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.TimeExtensionEncoder;
import io.github.dfa1.vortex.writer.encode.TimestampExtensionEncoder;
import io.github.dfa1.vortex.writer.encode.UuidExtensionEncoder;
import io.github.dfa1.vortex.writer.encode.VarBinEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.VarBinViewEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.ZigZagEncodingEncoder;
import io.github.dfa1.vortex.writer.encode.ZstdEncodingEncoder;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.BitVector;
import org.apache.arrow.vector.DateDayVector;
import org.apache.arrow.vector.FixedSizeBinaryVector;
import org.apache.arrow.vector.Float2Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.TimeMilliVector;
import org.apache.arrow.vector.TimeStampMilliVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.complex.ListVector;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.LongStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Cross-compatibility: Java writer → Rust (JNI) reader.
class JavaWritesRustReadsIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final DType.Struct SCHEMA = new DType.Struct(
            List.of("id", "value"),
            List.of(DType.I64,
                    DType.F64),
            false);

    private static final DType.Struct I32_SCHEMA = new DType.Struct(
            List.of("v"),
            List.of(DType.I32),
            false);

    private static final DType.Struct STRING_SCHEMA = new DType.Struct(
            List.of("s"),
            List.of(DType.UTF8),
            false);

    private static final DType.Struct TS_SCHEMA = new DType.Struct(
            List.of("ts"),
            List.of(DType.I64),
            false);

    private static final DType.Struct F32_SCHEMA = new DType.Struct(
            List.of("v"),
            List.of(DType.F32),
            false);

    private static final DType.Struct F64_SCHEMA = new DType.Struct(
            List.of("v"),
            List.of(DType.F64),
            false);

    private static final DType.Struct F16_SCHEMA = new DType.Struct(
            List.of("v"),
            List.of(DType.F16),
            false);

    private static final DType.Struct BOOL_SCHEMA = new DType.Struct(
            List.of("b"),
            List.of(DType.BOOL),
            false);

    private static final DType.Struct NULL_SCHEMA = new DType.Struct(
            List.of("n"),
            List.of(DType.NULL),
            false);

    private static final DType.Struct LIST_I64_SCHEMA = new DType.Struct(
            List.of("items"),
            List.of(new DType.List(DType.I64, false)),
            false);

    private static final DType.Struct OHLC_SCHEMA = new DType.Struct(
            List.of("date", "symbol", "open", "high", "low", "close", "volume"),
            List.of(
                    DType.I32,
                    DType.UTF8,
                    DType.F64,
                    DType.F64,
                    DType.F64,
                    DType.F64,
                    DType.I64),
            false);

    static {
        NativeLoader.loadJni();
    }

    private static long[] readLongColumn(Path file, String column) throws IOException {
        String uri = file.toAbsolutePath().toUri().toString();
        ScanOptions opts = ScanOptions.builder()
                                   .projection(Expression.select(new String[]{column}, Expression.root()))
                                   .build();
        var longs = new ArrayList<Long>();
        DataSource ds = DataSource.open(SESSION, uri);
        Scan scan = ds.scan(opts);
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    BigIntVector vec = (BigIntVector) root.getVector(column);
                    for (int i = 0; i < root.getRowCount(); i++) {
                        longs.add(vec.get(i));
                    }
                }
            }
        }
        return longs.stream().mapToLong(Long::longValue).toArray();
    }

    private static double[] readDoubleColumn(Path file, String column) throws IOException {
        String uri = file.toAbsolutePath().toUri().toString();
        ScanOptions opts = ScanOptions.builder()
                                   .projection(Expression.select(new String[]{column}, Expression.root()))
                                   .build();
        var doubles = new ArrayList<Double>();
        DataSource ds = DataSource.open(SESSION, uri);
        Scan scan = ds.scan(opts);
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    Float8Vector vec = (Float8Vector) root.getVector(column);
                    for (int i = 0; i < root.getRowCount(); i++) {
                        doubles.add(vec.get(i));
                    }
                }
            }
        }
        return doubles.stream().mapToDouble(Double::doubleValue).toArray();
    }

    private static float[] readFloatColumn(Path file, String column) throws IOException {
        String uri = file.toAbsolutePath().toUri().toString();
        ScanOptions opts = ScanOptions.builder()
                                   .projection(Expression.select(new String[]{column}, Expression.root()))
                                   .build();
        var floats = new ArrayList<Float>();
        DataSource ds = DataSource.open(SESSION, uri);
        Scan scan = ds.scan(opts);
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    org.apache.arrow.vector.Float4Vector vec = (org.apache.arrow.vector.Float4Vector) root.getVector(column);
                    for (int i = 0; i < root.getRowCount(); i++) {
                        floats.add(vec.get(i));
                    }
                }
            }
        }
        float[] result = new float[floats.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = floats.get(i);
        }
        return result;
    }

    private static short[] readHalfColumn(Path file, String column) throws IOException {
        String uri = file.toAbsolutePath().toUri().toString();
        ScanOptions opts = ScanOptions.builder()
                                   .projection(Expression.select(new String[]{column}, Expression.root()))
                                   .build();
        var halfs = new ArrayList<Short>();
        DataSource ds = DataSource.open(SESSION, uri);
        Scan scan = ds.scan(opts);
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    Float2Vector vec = (Float2Vector) root.getVector(column);
                    for (int i = 0; i < root.getRowCount(); i++) {
                        halfs.add(vec.get(i));
                    }
                }
            }
        }
        short[] result = new short[halfs.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = halfs.get(i);
        }
        return result;
    }

    private static int[] readIntColumn(Path file, String column) throws IOException {
        String uri = file.toAbsolutePath().toUri().toString();
        ScanOptions opts = ScanOptions.builder()
                                   .projection(Expression.select(new String[]{column}, Expression.root()))
                                   .build();
        var ints = new ArrayList<Integer>();
        DataSource ds = DataSource.open(SESSION, uri);
        Scan scan = ds.scan(opts);
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    IntVector vec = (IntVector) root.getVector(column);
                    for (int i = 0; i < root.getRowCount(); i++) {
                        ints.add(vec.get(i));
                    }
                }
            }
        }
        return ints.stream().mapToInt(Integer::intValue).toArray();
    }

    private static String[] readStringColumn(Path file, String column) throws IOException {
        String uri = file.toAbsolutePath().toUri().toString();
        ScanOptions opts = ScanOptions.builder()
                                   .projection(Expression.select(new String[]{column}, Expression.root()))
                                   .build();
        var strings = new ArrayList<String>();
        DataSource ds = DataSource.open(SESSION, uri);
        Scan scan = ds.scan(opts);
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    VarCharVector vec = (VarCharVector) root.getVector(column);
                    for (int i = 0; i < root.getRowCount(); i++) {
                        strings.add(vec.getObject(i).toString());
                    }
                }
            }
        }
        return strings.toArray(String[]::new);
    }

    // ── JNI read helpers ──────────────────────────────────────────────────────

    static Stream<Arguments> varBinViewStringArrayProvider() {
        return RandomArrays.varBinViewStringArrays(20).map(arr -> Arguments.of((Object) arr));
    }

    static Stream<Arguments> asciiStringArrayProvider() {
        return RandomArrays.asciiStringArrays(20).map(arr -> Arguments.of((Object) arr));
    }

    static Stream<Arguments> u16DictStringArrayProvider() {
        return RandomArrays.u16DictStringArrays(10).map(arr -> Arguments.of((Object) arr));
    }

    static Stream<Arguments> unicodeStringArrayProvider() {
        return RandomArrays.unicodeStringArrays(20).map(arr -> Arguments.of((Object) arr));
    }

    static Stream<long[]> i64ArrayProvider() {
        return RandomArrays.i64Arrays(30);
    }

    static Stream<double[]> f64ArrayProvider() {
        return RandomArrays.f64Arrays(30);
    }

    static Stream<float[]> f32ArrayProvider() {
        return RandomArrays.f32Arrays(30);
    }

    private static boolean[] readBoolColumn(Path file, String column) throws IOException {
        String uri = file.toAbsolutePath().toUri().toString();
        ScanOptions opts = ScanOptions.builder()
                                   .projection(Expression.select(new String[]{column}, Expression.root()))
                                   .build();
        var bools = new ArrayList<Boolean>();
        DataSource ds = DataSource.open(SESSION, uri);
        Scan scan = ds.scan(opts);
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    BitVector vec = (BitVector) root.getVector(column);
                    for (int i = 0; i < root.getRowCount(); i++) {
                        bools.add(!vec.isNull(i) && vec.get(i) == 1);
                    }
                }
            }
        }
        boolean[] result = new boolean[bools.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = bools.get(i);
        }
        return result;
    }

    private static long readRowCount(Path file) throws IOException {
        String uri = file.toAbsolutePath().toUri().toString();
        long count = 0;
        DataSource ds = DataSource.open(SESSION, uri);
        Scan scan = ds.scan(ScanOptions.of());
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
                while (reader.loadNextBatch()) {
                    count += reader.getVectorSchemaRoot().getRowCount();
                }
            }
        }
        return count;
    }

    private static long[] readListLongColumn(Path file, String column) throws IOException {
        String uri = file.toAbsolutePath().toUri().toString();
        ScanOptions opts = ScanOptions.builder()
                                   .projection(Expression.select(new String[]{column}, Expression.root()))
                                   .build();
        var elements = new ArrayList<Long>();
        DataSource ds = DataSource.open(SESSION, uri);
        Scan scan = ds.scan(opts);
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    ListVector vec = (ListVector) root.getVector(column);
                    BigIntVector child = (BigIntVector) vec.getDataVector();
                    for (int i = 0; i < root.getRowCount(); i++) {
                        int start = vec.getOffsetBuffer().getInt((long) i * ListVector.OFFSET_WIDTH);
                        int end = vec.getOffsetBuffer().getInt((long) (i + 1) * ListVector.OFFSET_WIDTH);
                        for (int j = start; j < end; j++) {
                            elements.add(child.get(j));
                        }
                    }
                }
            }
        }
        return elements.stream().mapToLong(Long::longValue).toArray();
    }

    private static void assertBitwiseEqualsF64(double[] actual, double[] expected) {
        assertThat(actual).hasSize(expected.length);
        for (int i = 0; i < expected.length; i++) {
            assertThat(Double.doubleToRawLongBits(actual[i]))
                    .as("element %d", i)
                    .isEqualTo(Double.doubleToRawLongBits(expected[i]));
        }
    }

    private static void assertBitwiseEqualsF32(float[] actual, float[] expected) {
        assertThat(actual).hasSize(expected.length);
        for (int i = 0; i < expected.length; i++) {
            assertThat(Float.floatToRawIntBits(actual[i]))
                    .as("element %d", i)
                    .isEqualTo(Float.floatToRawIntBits(expected[i]));
        }
    }

    private static void deleteDir(Path dir) throws IOException {
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }

    @Test
    void javaWriter_jniReader_cascading_ohlc(@TempDir Path tmp) throws IOException {
        // Given — OHLC data written with cascading(3): exercises ALP→FOR→bitpacked chain
        Path file = tmp.resolve("java_cascade_ohlc.vtx");
        List<OhlcData.Batch> batches = OhlcData.generate(10_000, 1_000);

        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, OHLC_SCHEMA, WriteOptions.cascading(3))) {
            for (OhlcData.Batch b : batches) {
                sut.writeChunk(Map.of(
                        "date", b.date(),
                        "symbol", b.symbol(),
                        "open", b.open(),
                        "high", b.high(),
                        "low", b.low(),
                        "close", b.close(),
                        "volume", b.volume()));
            }
        }

        // When
        long[] volumes = readLongColumn(file, "volume");
        double[] closes = readDoubleColumn(file, "close");

        // Then — JNI reader may return chunks in a different order for cascaded files;
        // verify all values round-trip correctly regardless of partition order.
        long[] expectedVolumes = batches.stream().flatMapToLong(b -> Arrays.stream(b.volume())).toArray();
        double[] expectedCloses = batches.stream().flatMapToDouble(b -> Arrays.stream(b.close())).toArray();
        assertThat(volumes).containsExactlyInAnyOrder(expectedVolumes);
        assertThat(closes).containsExactlyInAnyOrder(expectedCloses);
    }

    // ── Parameterized random-data tests ─────────────────────────────────────

    @Test
    void javaWriter_jniReader_singleChunk(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("java_single.vtx");
        long[] ids = {1L, 2L, 3L};
        double[] vals = {1.1, 2.2, 3.3};
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults())) {
            sut.writeChunk(Map.of("id", ids, "value", vals));
        }

        // When
        long[] decodedIds = readLongColumn(file, "id");
        double[] decodedVals = readDoubleColumn(file, "value");

        // Then
        assertThat(decodedIds).containsExactly(1L, 2L, 3L);
        assertThat(decodedVals).containsExactly(1.1, 2.2, 3.3);
    }

    @Test
    void javaWriter_jniReader_multipleChunks(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("java_multi.vtx");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults())) {
            sut.writeChunk(Map.of("id", new long[]{1L, 2L}, "value", new double[]{1.1, 2.2}));
            sut.writeChunk(Map.of("id", new long[]{3L, 4L, 5L}, "value", new double[]{3.3, 4.4, 5.5}));
        }

        // When
        long[] decodedIds = readLongColumn(file, "id");

        // Then — JNI may merge chunks; verify all values present regardless of partition count
        assertThat(decodedIds).containsExactly(1L, 2L, 3L, 4L, 5L);
    }

    @Test
    void javaWriter_jniReader_zoneMapped_multipleZones(@TempDir Path tmp) throws IOException {
        // Given — a small chunkSize forces several chunks, so the writer emits a vortex.stats
        // zone-map with one zone per chunk. The Rust reader must parse that layout and still
        // return every value (zones are a transparent pruning aux).
        Path file = tmp.resolve("java_zoned.vtx");
        WriteOptions zoneMapped = new WriteOptions(4, true, 0.90, 0, true, false);
        long[] ids = new long[20];
        double[] vals = new double[20];
        for (int i = 0; i < 20; i++) {
            ids[i] = i;
            vals[i] = i * 0.5;
        }
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, zoneMapped)) {
            for (int start = 0; start < 20; start += 4) {
                sut.writeChunk(Map.of(
                        "id", Arrays.copyOfRange(ids, start, start + 4),
                        "value", Arrays.copyOfRange(vals, start, start + 4)));
            }
        }

        // When
        long[] decodedIds = readLongColumn(file, "id");
        double[] decodedVals = readDoubleColumn(file, "value");

        // Then — all rows survive the Java zone-map -> Rust read round-trip
        assertThat(decodedIds).containsExactly(ids);
        assertThat(decodedVals).containsExactly(vals);
    }

    @Test
    void javaWriter_jniReader_i32Column(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("java_i32.vtx");
        int[] data = {-100, 0, 1, 127, Integer.MAX_VALUE, Integer.MIN_VALUE};
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, I32_SCHEMA, WriteOptions.defaults())) {
            // When
            sut.writeChunk(Map.of("v", data));
        }

        // Then
        int[] decoded = readIntColumn(file, "v");
        assertThat(decoded).containsExactly(data);
    }

    @Test
    void javaWriter_jniReader_utf8Column(@TempDir Path tmp) throws IOException {
        // Given — VarBin encoding (raw bytes + offsets, no dictionary)
        Path file = tmp.resolve("java_utf8.vtx");
        String[] data = {"apple", "banana", "cherry", "date", "elderberry"};
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, STRING_SCHEMA, WriteOptions.defaults(),
                     List.of(new VarBinEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of("s", data));
        }

        // Then
        String[] decoded = readStringColumn(file, "s");
        assertThat(decoded).containsExactly(data);
    }

    @Test
    void javaWriter_jniReader_fsstUtf8Column(@TempDir Path tmp) throws IOException {
        // Given — FSST encoding: bigram symbol table, escape fallback for unmatched bytes
        Path file = tmp.resolve("java_fsst_utf8.vtx");
        String[] data = {"apple", "banana", "cherry", "apricot", "avocado", "almond"};
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, STRING_SCHEMA, WriteOptions.defaults(),
                     List.of(new FsstEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of("s", data));
        }

        // Then
        String[] decoded = readStringColumn(file, "s");
        assertThat(decoded).containsExactly(data);
    }

    @Test
    void javaWriter_jniReader_varBinViewUtf8Column_inlined(@TempDir Path tmp) throws IOException {
        // Given — VarBinView, all strings ≤12 bytes: inlined path (no data buffer)
        Path file = tmp.resolve("java_varbinview_inlined.vtx");
        String[] data = {"hi", "yo", "ok", "abc", "short", "exactly12ok!"};
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, STRING_SCHEMA, WriteOptions.defaults(),
                     List.of(new VarBinViewEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of("s", data));
        }

        // Then
        String[] decoded = readStringColumn(file, "s");
        assertThat(decoded).containsExactly(data);
    }

    @Test
    void javaWriter_jniReader_varBinViewUtf8Column_referenced(@TempDir Path tmp) throws IOException {
        // Given — VarBinView, all strings >12 bytes: reference path (data buffer present)
        Path file = tmp.resolve("java_varbinview_referenced.vtx");
        String[] data = {"this is long text", "another long string", "yet another long one", "thirteenchars!"};
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, STRING_SCHEMA, WriteOptions.defaults(),
                     List.of(new VarBinViewEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of("s", data));
        }

        // Then
        String[] decoded = readStringColumn(file, "s");
        assertThat(decoded).containsExactly(data);
    }

    @Test
    void javaWriter_jniReader_varBinViewUtf8Column_mixed(@TempDir Path tmp) throws IOException {
        // Given — VarBinView, mix of inlined (≤12) and referenced (>12) strings
        Path file = tmp.resolve("java_varbinview_mixed.vtx");
        String[] data = {"short", "this is a longer string", "hi", "medium length ok", "x", "another longer string here"};
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, STRING_SCHEMA, WriteOptions.defaults(),
                     List.of(new VarBinViewEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of("s", data));
        }

        // Then
        String[] decoded = readStringColumn(file, "s");
        assertThat(decoded).containsExactly(data);
    }

    /// VarBinView utf8 with arbitrary strings — exercises both inlined and referenced views.
    @ParameterizedTest
    @MethodSource("varBinViewStringArrayProvider")
    void prop_varBinView_utf8_roundTripsViaRust(String[] data) throws IOException {
        Path tmp = Files.createTempDirectory("vortex-pbt-varbinview");
        try {
            Path file = tmp.resolve("pbt_varbinview_utf8.vtx");
            try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var sut = VortexWriter.create(ch, STRING_SCHEMA, WriteOptions.defaults(),
                         List.of(new VarBinViewEncodingEncoder()))) {
                sut.writeChunk(Map.of("s", data));
            }
            String[] decoded = readStringColumn(file, "s");
            assertThat(decoded).containsExactly(data);
        } finally {
            deleteDir(tmp);
        }
    }

    @Test
    void javaWriter_jniReader_dictEncodedUtf8Column(@TempDir Path tmp) throws IOException {
        // Given — DictEncoding (DictLayoutMetadata proto + children[0]=codes, children[1]=VarBin values)
        Path file = tmp.resolve("java_dict_utf8.vtx");
        String[] data = {"apple", "banana", "cherry", "date", "elderberry"};
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, STRING_SCHEMA, WriteOptions.defaults())) {
            // When — default pipeline selects DictEncoding for Utf8
            sut.writeChunk(Map.of("s", data));
        }

        // Then
        String[] decoded = readStringColumn(file, "s");
        assertThat(decoded).containsExactly(data);
    }

    @Test
    void javaWriter_jniReader_largeChunk_twoFastLanesBlocks(@TempDir Path tmp) throws IOException {
        // Given — 2048 rows = exactly 2 full 1024-element FastLanes blocks
        Path file = tmp.resolve("java_large.vtx");
        int n = 2048;
        long[] ids = new long[n];
        double[] vals = new double[n];
        for (int i = 0; i < n; i++) {
            ids[i] = i;
            vals[i] = i * 0.5;
        }
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults())) {
            // When
            sut.writeChunk(Map.of("id", ids, "value", vals));
        }

        // Then
        long[] decodedIds = readLongColumn(file, "id");
        assertThat(decodedIds).containsExactly(ids);
    }

    @Test
    void javaWriter_jniReader_monotonic_i64_cascading(@TempDir Path tmp) throws IOException {
        // Given — monotonic timestamps: FOR reduces to constant delta, bitpacked to ~10 bits
        Path file = tmp.resolve("java_ts.vtx");
        int n = 5_000;
        long[] ts = new long[n];
        for (int i = 0; i < n; i++) {
            ts[i] = 1_700_000_000L + (long) i * 1_000;
        }
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, TS_SCHEMA, WriteOptions.cascading(3))) {
            // When
            sut.writeChunk(Map.of("ts", ts));
        }

        // Then
        long[] decoded = readLongColumn(file, "ts");
        assertThat(decoded).containsExactly(ts);
    }

    @Test
    void javaWriter_jniReader_cascading_ohlc_columnProjection(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("java_cascade_proj.vtx");
        List<OhlcData.Batch> batches = OhlcData.generate(2_000, 1_000);
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, OHLC_SCHEMA, WriteOptions.cascading(3))) {
            for (OhlcData.Batch b : batches) {
                sut.writeChunk(Map.of(
                        "date", b.date(), "symbol", b.symbol(),
                        "open", b.open(), "high", b.high(),
                        "low", b.low(), "close", b.close(), "volume", b.volume()));
            }
        }

        // When — project only volume
        long[] volumes = readLongColumn(file, "volume");

        // Then
        long[] expected = batches.stream().flatMapToLong(b -> Arrays.stream(b.volume())).toArray();
        assertThat(volumes).containsExactlyInAnyOrder(expected);
    }

    /// Dict utf8 with arbitrary strings (small dict → U8 codes).
    /// Validates that random string data survives Java dict-encode → Rust JNI read.
    @ParameterizedTest
    @MethodSource("asciiStringArrayProvider")
    void prop_dictUtf8_ascii_roundTripsViaRust(String[] data) throws IOException {
        Path tmp = Files.createTempDirectory("vortex-pbt-ascii");
        try {
            Path file = tmp.resolve("pbt_dict_utf8_ascii.vtx");
            try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var sut = VortexWriter.create(ch, STRING_SCHEMA, WriteOptions.defaults())) {
                sut.writeChunk(Map.of("s", data));
            }
            String[] decoded = readStringColumn(file, "s");
            assertThat(decoded).containsExactly(data);
        } finally {
            deleteDir(tmp);
        }
    }

    /// Dict utf8 with 257+ unique strings → forces U16 codes (crosses U8→U16 boundary at 256).
    @ParameterizedTest
    @MethodSource("u16DictStringArrayProvider")
    void prop_dictUtf8_u16Codes_roundTripsViaRust(String[] data) throws IOException {
        Path tmp = Files.createTempDirectory("vortex-pbt-u16");
        try {
            Path file = tmp.resolve("pbt_dict_utf8_u16.vtx");
            try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var sut = VortexWriter.create(ch, STRING_SCHEMA, WriteOptions.defaults())) {
                sut.writeChunk(Map.of("s", data));
            }
            String[] decoded = readStringColumn(file, "s");
            assertThat(decoded).containsExactly(data);
        } finally {
            deleteDir(tmp);
        }
    }

    /// Dict utf8 with unicode strings (multi-byte UTF-8, CJK).
    @ParameterizedTest
    @MethodSource("unicodeStringArrayProvider")
    void prop_dictUtf8_unicode_roundTripsViaRust(String[] data) throws IOException {
        Path tmp = Files.createTempDirectory("vortex-pbt-unicode");
        try {
            Path file = tmp.resolve("pbt_dict_utf8_unicode.vtx");
            try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var sut = VortexWriter.create(ch, STRING_SCHEMA, WriteOptions.defaults())) {
                sut.writeChunk(Map.of("s", data));
            }
            String[] decoded = readStringColumn(file, "s");
            assertThat(decoded).containsExactly(data);
        } finally {
            deleteDir(tmp);
        }
    }

    /// I64 column: full Long range (MIN_VALUE, MAX_VALUE, negatives), empty and large arrays.
    @ParameterizedTest
    @MethodSource("i64ArrayProvider")
    void prop_i64_roundTripsViaRust(long[] data) throws IOException {
        Path tmp = Files.createTempDirectory("vortex-pbt-i64");
        try {
            Path file = tmp.resolve("pbt_i64.vtx");
            try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var sut = VortexWriter.create(ch, TS_SCHEMA, WriteOptions.defaults())) {
                sut.writeChunk(Map.of("ts", data));
            }
            long[] decoded = readLongColumn(file, "ts");
            assertThat(decoded).containsExactly(data);
        } finally {
            deleteDir(tmp);
        }
    }

    /// F64 column: finite doubles (ALP encoding), empty and large arrays.
    @ParameterizedTest
    @MethodSource("f64ArrayProvider")
    void prop_f64_roundTripsViaRust(double[] data) throws IOException {
        Path tmp = Files.createTempDirectory("vortex-pbt-f64");
        try {
            Path file = tmp.resolve("pbt_f64.vtx");
            try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var sut = VortexWriter.create(ch, F64_SCHEMA, WriteOptions.defaults())) {
                sut.writeChunk(Map.of("v", data));
            }
            double[] decoded = readDoubleColumn(file, "v");
            assertThat(decoded).containsExactly(data);
        } finally {
            deleteDir(tmp);
        }
    }

    /// F32 column: finite floats, empty and large arrays.
    @ParameterizedTest
    @MethodSource("f32ArrayProvider")
    void prop_f32_roundTripsViaRust(float[] data) throws IOException {
        Path tmp = Files.createTempDirectory("vortex-pbt-f32");
        try {
            Path file = tmp.resolve("pbt_f32.vtx");
            try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var sut = VortexWriter.create(ch, F32_SCHEMA, WriteOptions.defaults())) {
                sut.writeChunk(Map.of("v", data));
            }
            float[] decoded = readFloatColumn(file, "v");
            assertThat(decoded).containsExactly(data);
        } finally {
            deleteDir(tmp);
        }
    }

    /// F64: NaN and Inf go to ALP patches (raw bits). containsExactly fails for NaN — use bitwise comparison.
    @Test
    void javaWriter_jniReader_f64_nanAndInf(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("f64_nan_inf.vtx");
        double[] data = {Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, 0.0, 1.5, -1.5};
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, F64_SCHEMA, WriteOptions.defaults())) {
            // When
            sut.writeChunk(Map.of("v", data));
        }

        // Then
        double[] decoded = readDoubleColumn(file, "v");
        assertBitwiseEqualsF64(decoded, data);
    }

    /// F32: same as F64 — NaN and Inf go to ALP patches, require bitwise comparison.
    @Test
    void javaWriter_jniReader_f32_nanAndInf(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("f32_nan_inf.vtx");
        float[] data = {Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, 0.0f, 1.5f, -1.5f};
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, F32_SCHEMA, WriteOptions.defaults())) {
            // When
            sut.writeChunk(Map.of("v", data));
        }

        // Then
        float[] decoded = readFloatColumn(file, "v");
        assertBitwiseEqualsF32(decoded, data);
    }

    /// F16: PrimitiveEncoding stores raw short bits — NaN and Inf are just bit patterns.
    /// Disabled: vortex-jni does not export F16 via Arrow C Data Interface (not fixed as of 0.74.0).
    @Disabled
    @Test
    void javaWriter_jniReader_f16_nanAndInf(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("f16_nan_inf.vtx");
        // Half-precision: +Inf=0x7C00, -Inf=0xFC00, quiet NaN=0x7E00, 1.0=0x3C00, 0.0=0x0000
        short[] data = {(short) 0x7E00, (short) 0x7C00, (short) 0xFC00, (short) 0x3C00, (short) 0x0000};
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, F16_SCHEMA, WriteOptions.defaults())) {
            // When
            sut.writeChunk(Map.of("v", data));
        }

        // Then
        short[] decoded = readHalfColumn(file, "v");
        assertThat(decoded).containsExactly(data);
    }

    @Test
    void javaWriter_rustReader_zigzag_i32(@TempDir Path tmp) throws IOException {
        // Given — ZigZag: signed integers with negatives exercise both encode paths
        Path file = tmp.resolve("java_zigzag_i32.vtx");
        int[] data = {-1000, -1, 0, 1, 127, -127, Integer.MIN_VALUE / 2, Integer.MAX_VALUE / 2};
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, I32_SCHEMA, WriteOptions.defaults(),
                     List.of(new ZigZagEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of("v", data));
        }

        // Then
        int[] decoded = readIntColumn(file, "v");
        assertThat(decoded).containsExactly(data);
    }

    @Test
    void javaWriter_rustReader_zigzag_i64(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("java_zigzag_i64.vtx");
        long[] data = {Long.MIN_VALUE / 2, -1L, 0L, 1L, Long.MAX_VALUE / 2};
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, TS_SCHEMA, WriteOptions.defaults(),
                     List.of(new ZigZagEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of("ts", data));
        }

        // Then
        long[] decoded = readLongColumn(file, "ts");
        assertThat(decoded).containsExactly(data);
    }

    @Test
    void javaWriter_rustReader_runEnd_i32(@TempDir Path tmp) throws IOException {
        // Given — RunEnd: runs of same value exercise the run-length proto serialization
        Path file = tmp.resolve("java_runend_i32.vtx");
        int[] data = {10, 10, 10, 20, 20, 30, 30, 30, 30};
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, I32_SCHEMA, WriteOptions.defaults(),
                     List.of(new RunEndEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of("v", data));
        }

        // Then
        int[] decoded = readIntColumn(file, "v");
        assertThat(decoded).containsExactly(data);
    }

    @Test
    void javaWriter_rustReader_runEnd_i64(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("java_runend_i64.vtx");
        long[] data = {100L, 100L, 200L, 200L, 200L, 300L};
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, TS_SCHEMA, WriteOptions.defaults(),
                     List.of(new RunEndEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of("ts", data));
        }

        // Then
        long[] decoded = readLongColumn(file, "ts");
        assertThat(decoded).containsExactly(data);
    }

    @Test
    void javaWriter_rustReader_rle_i32(@TempDir Path tmp) throws IOException {
        // Given — FastLanes RLE: chunk-based RLE with offset; exercises chunk boundary proto fields
        Path file = tmp.resolve("java_rle_i32.vtx");
        int[] data = {5, 5, 5, 7, 7, 5, 5, 5, 5, 9};
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, I32_SCHEMA, WriteOptions.defaults(),
                     List.of(new RleEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of("v", data));
        }

        // Then
        int[] decoded = readIntColumn(file, "v");
        assertThat(decoded).containsExactly(data);
    }

    @Test
    void javaWriter_rustReader_rle_i64(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("java_rle_i64.vtx");
        long[] data = {1L, 1L, 1L, 2L, 2L, 2L, 3L, 3L};
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, TS_SCHEMA, WriteOptions.defaults(),
                     List.of(new RleEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of("ts", data));
        }

        // Then
        long[] decoded = readLongColumn(file, "ts");
        assertThat(decoded).containsExactly(data);
    }

    @Test
    void javaWriter_rustReader_constant_i32(@TempDir Path tmp) throws IOException {
        // Given — Constant: scalar proto serialization and row count metadata
        Path file = tmp.resolve("java_constant_i32.vtx");
        int[] data = {42, 42, 42, 42, 42};
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, I32_SCHEMA, WriteOptions.defaults(),
                     List.of(new ConstantEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of("v", data));
        }

        // Then
        int[] decoded = readIntColumn(file, "v");
        assertThat(decoded).containsExactly(data);
    }

    @Test
    void javaWriter_rustReader_sparse_i32(@TempDir Path tmp) throws IOException {
        // Given — Sparse: mostly-zero data; exercises patch indices + endianness of offset field
        Path file = tmp.resolve("java_sparse_i32.vtx");
        int[] data = {0, 0, 7, 0, 0, 0, 13, 0, 0, 0};
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, I32_SCHEMA, WriteOptions.defaults(),
                     List.of(new SparseEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of("v", data));
        }

        // Then
        int[] decoded = readIntColumn(file, "v");
        assertThat(decoded).containsExactly(data);
    }

    @Test
    void javaWriter_rustReader_delta_i64(@TempDir Path tmp) throws IOException {
        // Given — FastLanes delta: monotonic data with varied positive steps. The encoder pads to a
        // 1024-element chunk and stores per-lane bases + deltas; Rust must trim back to n rows.
        Path file = tmp.resolve("java_delta_i64.vtx");
        long[] data = new long[50];
        long acc = 1_000L;
        for (int i = 0; i < data.length; i++) {
            acc += 1 + (i % 7);
            data[i] = acc;
        }
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, TS_SCHEMA, WriteOptions.defaults(),
                     List.of(new DeltaEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of("ts", data));
        }

        // Then
        long[] decoded = readLongColumn(file, "ts");
        assertThat(decoded).containsExactly(data);
    }

    @Test
    void javaWriter_rustReader_masked_nullableI64(@TempDir Path tmp) throws IOException {
        // Given — nullable primitive I64 with interleaved nulls. The default write path wraps a
        // nullable column as MaskedEncoding → PrimitiveEncoding (values + validity bool child).
        // This is the direct Masked-over-primitive layout; nullable_date only covers Masked → Ext.
        Path file = tmp.resolve("java_masked_i64.vtx");
        DType.Struct schema = new DType.Struct(
                List.of("v"),
                List.of(new DType.Primitive(PType.I64, true)),
                false);
        Long[] data = {10L, null, 30L, null, 50L, 60L};
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            // When — boxed Long[] via the map entry point routes through the nullable → masked path
            sut.writeChunk(Map.of("v", data));
        }

        // Then — Rust reads a nullable BigInt vector; null positions survive, values round-trip
        String uri = file.toAbsolutePath().toUri().toString();
        DataSource ds = DataSource.open(SESSION, uri);
        Scan scan = ds.scan(ScanOptions.of());
        var values = new ArrayList<Long>();
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    BigIntVector vec = (BigIntVector) root.getVector("v");
                    for (int i = 0; i < root.getRowCount(); i++) {
                        values.add(vec.isNull(i) ? null : vec.get(i));
                    }
                }
            }
        }
        assertThat(values).containsExactly(10L, null, 30L, null, 50L, 60L);
    }

    @Test
    void javaWriter_rustReader_masked_nullableUtf8(@TempDir Path tmp) throws IOException {
        // Given — nullable Utf8 via the default write path. Nullable utf8 now unifies on the
        // NullableData carrier, so it routes through MaskedEncoding → VarBin (values + validity)
        // like nullable primitives. Regression guard: this path used to NPE in VarBin on the null
        // element because no masked wrapper was inserted for String[]-with-nulls.
        Path file = tmp.resolve("java_masked_utf8.vtx");
        DType.Struct schema = new DType.Struct(List.of("s"), List.of(new DType.Utf8(true)), false);
        String[] data = {"alpha", null, "gamma", null, "epsilon"};
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            // When
            sut.writeChunk(Map.of("s", data));
        }

        // Then — Rust reads the nullable Utf8 vector; null positions survive, values round-trip
        String uri = file.toAbsolutePath().toUri().toString();
        DataSource ds = DataSource.open(SESSION, uri);
        Scan scan = ds.scan(ScanOptions.of());
        var values = new ArrayList<String>();
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    VarCharVector vec = (VarCharVector) root.getVector("s");
                    for (int i = 0; i < root.getRowCount(); i++) {
                        values.add(vec.isNull(i) ? null : vec.getObject(i).toString());
                    }
                }
            }
        }
        assertThat(values).containsExactly("alpha", null, "gamma", null, "epsilon");
    }

    @Test
    void javaWriter_rustReader_bool_boolEncoding(@TempDir Path tmp) throws IOException {
        // Given — BoolEncoding: bit-packed boolean column
        Path file = tmp.resolve("java_bool.vtx");
        boolean[] data = {true, false, true, true, false, false, true};
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, BOOL_SCHEMA, WriteOptions.defaults(),
                     List.of(new BoolEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of("b", data));
        }

        // Then
        boolean[] decoded = readBoolColumn(file, "b");
        assertThat(decoded).containsExactly(data[0], data[1], data[2], data[3], data[4], data[5], data[6]);
    }

    // ── new reader helpers ────────────────────────────────────────────────────

    @Test
    void javaWriter_rustReader_bool_byteBoolEncoding(@TempDir Path tmp) throws IOException {
        // Given — ByteBoolEncoding: one byte per bool (uncompressed), exercises different wire format
        Path file = tmp.resolve("java_bytebool.vtx");
        boolean[] data = {false, true, false, true, true};
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, BOOL_SCHEMA, WriteOptions.defaults(),
                     List.of(new ByteBoolEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of("b", data));
        }

        // Then
        boolean[] decoded = readBoolColumn(file, "b");
        assertThat(decoded).containsExactly(data[0], data[1], data[2], data[3], data[4]);
    }

    @Test
    void javaWriter_rustReader_nullColumn(@TempDir Path tmp) throws IOException {
        // Given — NullEncoding: entire column is null; exercises Null DType + empty EncodeNode
        Path file = tmp.resolve("java_null.vtx");
        long rowCount = 7L;
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, NULL_SCHEMA, WriteOptions.defaults(),
                     List.of(new NullEncodingEncoder()))) {
            // When — data is ignored by NullEncoding; pass long[] to satisfy arrayLength
            sut.writeChunk(Map.of("n", new long[(int) rowCount]));
        }

        // Then
        long count = readRowCount(file);
        assertThat(count).isEqualTo(rowCount);
    }

    @Test
    void javaWriter_rustReader_zstd_i64(@TempDir Path tmp) throws IOException {
        // Given — ZstdEncoding: compressed primitive; exercises zstd frame magic + frame header
        Path file = tmp.resolve("java_zstd_i64.vtx");
        long[] data = {1L, 2L, 3L, 4L, 1000L, 9999L, Long.MAX_VALUE};
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, TS_SCHEMA, WriteOptions.defaults(),
                     List.of(new ZstdEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of("ts", data));
        }

        // Then
        long[] decoded = readLongColumn(file, "ts");
        assertThat(decoded).containsExactly(data);
    }

    @Test
    void javaWriter_rustReader_zstd_utf8(@TempDir Path tmp) throws IOException {
        // Given — ZstdEncoding: compressed Utf8; exercises length-prefix framing + zstd magic
        Path file = tmp.resolve("java_zstd_utf8.vtx");
        String[] data = {"hello", "world", "from", "zstd"};
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, STRING_SCHEMA, WriteOptions.defaults(),
                     List.of(new ZstdEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of("s", data));
        }

        // Then
        String[] decoded = readStringColumn(file, "s");
        assertThat(decoded).containsExactly(data);
    }

    @Test
    void javaWriter_rustReader_zstd_multiFrameI64(@TempDir Path tmp) throws IOException {
        // Given — ZstdEncoding split into frames of 3 values: 7 values -> 3 frames (3, 3, 1), each
        // an independently compressed zstd frame with its own ZstdFrameMetadata. Verifies the
        // multi-frame wire layout against the Rust reader.
        Path file = tmp.resolve("java_zstd_multiframe_i64.vtx");
        long[] data = {1L, 2L, 3L, 4L, 5L, 6L, 7L};
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, TS_SCHEMA, WriteOptions.defaults(),
                     List.of(new ZstdEncodingEncoder(3)))) {
            // When
            sut.writeChunk(Map.of("ts", data));
        }

        // Then
        long[] decoded = readLongColumn(file, "ts");
        assertThat(decoded).containsExactly(data);
    }

    @Test
    void javaWriter_rustReader_zstd_nullableI64(@TempDir Path tmp) throws IOException {
        // Given — nullable primitive I64 written with ZstdEncoding. A configured zstd encoder
        // declares acceptsNullable, so the writer routes the NullableData straight to it instead
        // of masked-wrapping: zstd strips nulls before compression and emits validity as child[0].
        // Verifies that nullable-zstd layout against the Rust reader.
        Path file = tmp.resolve("java_zstd_nullable_i64.vtx");
        DType.Struct schema = new DType.Struct(List.of("v"), List.of(new DType.Primitive(PType.I64, true)), false);
        Long[] data = {10L, null, 30L, null, 50L, 60L};
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, schema, WriteOptions.defaults(),
                     List.of(new ZstdEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of("v", data));
        }

        // Then — Rust reads a nullable BigInt vector; null positions survive, values round-trip
        String uri = file.toAbsolutePath().toUri().toString();
        DataSource ds = DataSource.open(SESSION, uri);
        Scan scan = ds.scan(ScanOptions.of());
        var values = new ArrayList<Long>();
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    BigIntVector vec = (BigIntVector) root.getVector("v");
                    for (int i = 0; i < root.getRowCount(); i++) {
                        values.add(vec.isNull(i) ? null : vec.get(i));
                    }
                }
            }
        }
        assertThat(values).containsExactly(10L, null, 30L, null, 50L, 60L);
    }

    @Test
    void javaWriter_rustReader_zstd_nullableUtf8(@TempDir Path tmp) throws IOException {
        // Given — nullable Utf8 written with ZstdEncoding. Nullable string columns reach the zstd
        // encoder directly (a String[] carrying nulls is not the NullableData shape that
        // writeSegment masked-wraps), so this exercises the encoder's nullable varbin path against
        // the Rust reader: nulls stripped before compression, validity emitted as child[0].
        Path file = tmp.resolve("java_zstd_nullable_utf8.vtx");
        DType.Struct schema = new DType.Struct(List.of("s"), List.of(new DType.Utf8(true)), false);
        String[] data = {"hello", null, "world", null};
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, schema, WriteOptions.defaults(),
                     List.of(new ZstdEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of("s", data));
        }

        // Then — Rust reads the nullable Utf8 vector; null positions survive, values round-trip
        String uri = file.toAbsolutePath().toUri().toString();
        DataSource ds = DataSource.open(SESSION, uri);
        Scan scan = ds.scan(ScanOptions.of());
        var values = new ArrayList<String>();
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    VarCharVector vec = (VarCharVector) root.getVector("s");
                    for (int i = 0; i < root.getRowCount(); i++) {
                        values.add(vec.isNull(i) ? null : vec.getObject(i).toString());
                    }
                }
            }
        }
        assertThat(values).containsExactly("hello", null, "world", null);
    }

    @Test
    void javaWriter_rustReader_list_i64(@TempDir Path tmp) throws IOException {
        // Given — ListEncoding: exercises elements_len + offset_ptype proto fields (byte-order risk)
        Path file = tmp.resolve("java_list_i64.vtx");
        long[] elements = {10L, 20L, 30L, 40L, 50L, 60L};
        long[] offsets = {0L, 2L, 2L, 5L, 6L};  // lists: [10,20], [], [30,40,50], [60]
        ListData data = new ListData(elements, offsets, 4L);
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, LIST_I64_SCHEMA, WriteOptions.defaults(),
                     List.of(new ListEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of("items", data));
        }

        // Then
        long[] flatElements = readListLongColumn(file, "items");
        assertThat(flatElements).containsExactly(elements);
    }

    @Test
    void javaWriter_rustReader_listView_i64(@TempDir Path tmp) throws IOException {
        // Given — ListViewEncoding: offset+size pairs in proto; exercises U16 vs U32 ptype risk
        Path file = tmp.resolve("java_listview_i64.vtx");
        long[] elements = {1L, 2L, 3L, 4L, 5L};
        int[] offsets = {0, 2, 2, 4};    // lists start at these positions
        int[] sizes = {2, 0, 2, 1};       // list lengths: [1,2], [], [3,4], [5]
        ListViewData data = new ListViewData(elements, offsets, sizes, 4L);
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, LIST_I64_SCHEMA, WriteOptions.defaults(),
                     List.of(new ListViewEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of("items", data));
        }

        // Then — Rust normalizes ListView to List on read; verify flattened elements
        long[] flatElements = readListLongColumn(file, "items");
        assertThat(flatElements).containsExactly(elements);
    }

    @Test
    void javaWriter_rustReader_cascading_withZstd_f64(@TempDir Path tmp) throws IOException {
        // Given — Zstd wins the cascade for high-cardinality F64; Rust must decode vortex.zstd
        Path file = tmp.resolve("java_zstd_cascade_f64.vtx");
        int n = 2_000;
        double[] data = new double[n];
        for (int i = 0; i < n; i++) {
            data[i] = i * 1.37 + 3.14;
        }
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, F64_SCHEMA, WriteOptions.cascading(3).withZstd(true))) {
            // When
            sut.writeChunk(Map.of("v", data));
        }

        // Then — Rust reader must decode the file (whether via ALP or Zstd, value-equal)
        double[] decoded = readDoubleColumn(file, "v");
        assertBitwiseEqualsF64(decoded, data);
    }

    @Test
    void javaWriter_rustReader_globalDict_i64(@TempDir Path tmp) throws IOException {
        // Given — low-cardinality I64 column triggers global dict across two chunks
        Path file = tmp.resolve("java_globaldict_i64.vtx");
        DType.Struct schema = new DType.Struct(
                List.of("v"),
                List.of(DType.I64),
                false);
        long[] chunk1 = {1L, 2L, 3L, 1L, 2L, 3L, 1L, 2L};
        long[] chunk2 = {3L, 1L, 2L, 3L, 1L, 2L, 3L, 1L};
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            // When
            sut.writeChunk(Map.of("v", chunk1));
            sut.writeChunk(Map.of("v", chunk2));
        }

        // Then — Rust must decode the DictLayout and return original values
        long[] all = new long[chunk1.length + chunk2.length];
        System.arraycopy(chunk1, 0, all, 0, chunk1.length);
        System.arraycopy(chunk2, 0, all, chunk1.length, chunk2.length);
        long[] decoded = readLongColumn(file, "v");
        assertThat(decoded).containsExactly(all);
    }

    @Test
    void javaWriter_rustReader_globalDict_f64(@TempDir Path tmp) throws IOException {
        // Given — low-cardinality F64 column (e.g. a status field stored as float) triggers global dict
        Path file = tmp.resolve("java_globaldict_f64.vtx");
        DType.Struct schema = new DType.Struct(
                List.of("v"),
                List.of(DType.F64),
                false);
        double[] chunk1 = {1.0, 2.0, 3.0, 1.0, 2.0, 3.0, 1.0, 2.0};
        double[] chunk2 = {3.0, 1.0, 2.0, 3.0, 1.0, 2.0, 3.0, 1.0};
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            // When
            sut.writeChunk(Map.of("v", chunk1));
            sut.writeChunk(Map.of("v", chunk2));
        }

        // Then
        double[] all = new double[chunk1.length + chunk2.length];
        System.arraycopy(chunk1, 0, all, 0, chunk1.length);
        System.arraycopy(chunk2, 0, all, chunk1.length, chunk2.length);
        double[] decoded = readDoubleColumn(file, "v");
        assertThat(decoded).containsExactly(all);
    }

    @Test
    void javaWriter_rustReader_nullable_date(@TempDir Path tmp) throws IOException {
        // Given — nullable vortex.date column; row 1 is null.
        // Validates MaskedEncoding → ExtEncoding → PrimitiveEncoding layout survives Java→Rust read.
        Path file = tmp.resolve("java_nullable_date.vtx");
        LocalDate d0 = LocalDate.of(1996, 2, 12);
        LocalDate d2 = LocalDate.of(2026, 6, 10);
        DType.Extension dateDtype = DateExtensionEncoder.INSTANCE.dtype(true);
        DType.Struct schema = new DType.Struct(List.of("d"), List.of(dateDtype), false);
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            // When
            sut.writeChunk(Map.of("d", Arrays.asList(d0, null, d2)));
        }

        // Then — Rust reads Date32 vector; row 1 null, rows 0/2 round-trip as epoch-day
        String uri = file.toAbsolutePath().toUri().toString();
        DataSource ds = DataSource.open(SESSION, uri);
        Scan scan = ds.scan(ScanOptions.of());
        var dates = new ArrayList<LocalDate>();
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    DateDayVector vec = (DateDayVector) root.getVector("d");
                    for (int i = 0; i < root.getRowCount(); i++) {
                        dates.add(vec.isNull(i) ? null : LocalDate.ofEpochDay(vec.get(i)));
                    }
                }
            }
        }
        assertThat(dates).containsExactly(d0, null, d2);
    }

    @Test
    void javaWriter_rustReader_nullable_time(@TempDir Path tmp) throws IOException {
        // Given — nullable vortex.time(ms) column; row 1 is null.
        // Default unit is milliseconds → int32 storage → Arrow Time32(ms) on read.
        Path file = tmp.resolve("java_nullable_time.vtx");
        LocalTime t0 = LocalTime.of(1, 1, 1);
        LocalTime t2 = LocalTime.of(12, 30, 0);
        DType.Extension timeDtype = TimeExtensionEncoder.INSTANCE.dtype(true);
        DType.Struct schema = new DType.Struct(List.of("t"), List.of(timeDtype), false);
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            // When
            sut.writeChunk(Map.of("t", Arrays.asList(t0, null, t2)));
        }

        // Then — Rust reads Time32(ms) vector; row 1 null, rows 0/2 round-trip as millis-of-day
        String uri = file.toAbsolutePath().toUri().toString();
        DataSource ds = DataSource.open(SESSION, uri);
        Scan scan = ds.scan(ScanOptions.of());
        var times = new ArrayList<LocalTime>();
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    TimeMilliVector vec = (TimeMilliVector) root.getVector("t");
                    for (int i = 0; i < root.getRowCount(); i++) {
                        times.add(vec.isNull(i) ? null : LocalTime.ofNanoOfDay((long) vec.get(i) * 1_000_000L));
                    }
                }
            }
        }
        assertThat(times).containsExactly(t0, null, t2);
    }

    @Test
    void javaWriter_rustReader_nullable_timestamp(@TempDir Path tmp) throws IOException {
        // Given — nullable vortex.timestamp(ms, no tz) column; row 1 is null.
        // Default unit is milliseconds → int64 storage → Arrow Timestamp(ms) on read.
        Path file = tmp.resolve("java_nullable_timestamp.vtx");
        Instant ts0 = Instant.ofEpochMilli(1_749_538_800_000L);
        Instant ts2 = Instant.ofEpochMilli(0L);
        DType.Extension tsDtype = TimestampExtensionEncoder.INSTANCE.dtype(true);
        DType.Struct schema = new DType.Struct(List.of("ts"), List.of(tsDtype), false);
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            // When
            sut.writeChunk(Map.of("ts", Arrays.asList(ts0, null, ts2)));
        }

        // Then — Rust reads Timestamp(ms) vector; row 1 null, rows 0/2 round-trip as epoch millis
        String uri = file.toAbsolutePath().toUri().toString();
        DataSource ds = DataSource.open(SESSION, uri);
        Scan scan = ds.scan(ScanOptions.of());
        var timestamps = new ArrayList<Instant>();
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    TimeStampMilliVector vec = (TimeStampMilliVector) root.getVector("ts");
                    for (int i = 0; i < root.getRowCount(); i++) {
                        timestamps.add(vec.isNull(i) ? null : Instant.ofEpochMilli(vec.get(i)));
                    }
                }
            }
        }
        assertThat(timestamps).containsExactly(ts0, null, ts2);
    }

    @Test
    @Disabled("vortex-jni 0.74.0 uses the non-plugin-aware DType.to_arrow_schema() which bails "
            + "for any extension type other than parquet.variant — including vortex.uuid. "
            + "Re-enable when the JNI is updated to use ArrowSession.to_arrow_schema().")
    void javaWriter_rustReader_nullable_uuid(@TempDir Path tmp) throws IOException {
        // Given — nullable vortex.uuid column; row 1 is null.
        // UUID stored as big-endian 16-byte FixedSizeBinary → Arrow FixedSizeBinary(16) on read.
        Path file = tmp.resolve("java_nullable_uuid.vtx");
        UUID u0 = UUID.fromString("12345678-1234-5678-9abc-def012345678");
        UUID u2 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        DType.Extension uuidDtype = UuidExtensionEncoder.INSTANCE.dtype(true);
        DType.Struct schema = new DType.Struct(List.of("u"), List.of(uuidDtype), false);
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            // When
            sut.writeChunk(Map.of("u", Arrays.asList(u0, null, u2)));
        }

        // Then — Rust reads FixedSizeBinary(16) vector; row 1 null, rows 0/2 round-trip as UUID
        String uri = file.toAbsolutePath().toUri().toString();
        DataSource ds = DataSource.open(SESSION, uri);
        Scan scan = ds.scan(ScanOptions.of());
        var uuids = new ArrayList<UUID>();
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    FixedSizeBinaryVector vec = (FixedSizeBinaryVector) root.getVector("u");
                    for (int i = 0; i < root.getRowCount(); i++) {
                        uuids.add(vec.isNull(i) ? null : uuidFromBytes(vec.get(i)));
                    }
                }
            }
        }
        assertThat(uuids).containsExactly(u0, null, u2);
    }

    // ── PCO (vortex.pco) Java→Rust tests ─────────────────────────────────────

    @Test
    void javaWriter_rustReader_pco_i64_noOp(@TempDir Path tmp) throws IOException {
        // Given — non-sequential I64: NoOp delta wins (range compression only)
        Path file = tmp.resolve("java_pco_i64_noop.vtx");
        long[] data = {Long.MIN_VALUE, -1_000_000L, -1L, 0L, 1L, 1_000_000L, Long.MAX_VALUE};
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, TS_SCHEMA, WriteOptions.defaults(),
                     List.of(new PcoEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of("ts", data));
        }

        // Then
        long[] decoded = readLongColumn(file, "ts");
        assertThat(decoded).containsExactly(data);
    }

    @Test
    void javaWriter_rustReader_pco_i64_consecutive(@TempDir Path tmp) throws IOException {
        // Given — sequential I64: Consecutive delta wins (constant stride → 0 bits/element after moment)
        Path file = tmp.resolve("java_pco_i64_consecutive.vtx");
        long[] data = LongStream.range(1_700_000_000_000L, 1_700_000_001_000L).toArray();
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, TS_SCHEMA, WriteOptions.defaults(),
                     List.of(new PcoEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of("ts", data));
        }

        // Then
        long[] decoded = readLongColumn(file, "ts");
        assertThat(decoded).containsExactly(data);
    }

    @Test
    void javaWriter_rustReader_pco_i32(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("java_pco_i32.vtx");
        int[] data = {Integer.MIN_VALUE, -1_000, -1, 0, 1, 1_000, Integer.MAX_VALUE};
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, I32_SCHEMA, WriteOptions.defaults(),
                     List.of(new PcoEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of("v", data));
        }

        // Then
        int[] decoded = readIntColumn(file, "v");
        assertThat(decoded).containsExactly(data);
    }

    @Test
    void javaWriter_rustReader_pco_f64(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("java_pco_f64.vtx");
        double[] data = {-1.5, -1.0, -0.5, 0.0, 0.5, 1.0, 1.5, Math.PI, Math.E};
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, F64_SCHEMA, WriteOptions.defaults(),
                     List.of(new PcoEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of("v", data));
        }

        // Then
        double[] decoded = readDoubleColumn(file, "v");
        assertBitwiseEqualsF64(decoded, data);
    }

    @Test
    void javaWriter_rustReader_pco_f32(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("java_pco_f32.vtx");
        float[] data = {-1.5f, -1.0f, -0.5f, 0.0f, 0.5f, 1.0f, 1.5f, (float) Math.PI};
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, F32_SCHEMA, WriteOptions.defaults(),
                     List.of(new PcoEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of("v", data));
        }

        // Then
        float[] decoded = readFloatColumn(file, "v");
        assertBitwiseEqualsF32(decoded, data);
    }

    @Test
    void javaWriter_rustReader_pco_i64_twoChunks(@TempDir Path tmp) throws IOException {
        // Given — 150K sequential I64 values: crosses the 64K chunk boundary twice
        Path file = tmp.resolve("java_pco_i64_twochunks.vtx");
        long[] data = LongStream.range(0, 150_000).toArray();
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, TS_SCHEMA, WriteOptions.defaults(),
                     List.of(new PcoEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of("ts", data));
        }

        // Then
        long[] decoded = readLongColumn(file, "ts");
        assertThat(decoded).containsExactly(data);
    }

    @Test
    void javaWriter_rustReader_pco_i64_multiChunk(@TempDir Path tmp) throws IOException {
        // Given — 200K sequential I64 values: crosses the 64K chunk boundary three times
        Path file = tmp.resolve("java_pco_i64_multichunk.vtx");
        long[] data = LongStream.range(0, 200_000).toArray();
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, TS_SCHEMA, WriteOptions.defaults(),
                     List.of(new PcoEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of("ts", data));
        }

        // Then
        long[] decoded = readLongColumn(file, "ts");
        assertThat(decoded).containsExactly(data);
    }

    @Test
    void javaWriter_rustReader_pco_i64_intMult(@TempDir Path tmp) throws IOException {
        // Given — values × 1000: triple-GCD detection picks base=1000 → mode=1
        Path file = tmp.resolve("java_pco_i64_intmult.vtx");
        long[] data = LongStream.range(0, 2000).map(i -> i * 1000L).toArray();
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, TS_SCHEMA, WriteOptions.defaults(),
                     List.of(new PcoEncodingEncoder()))) {
            // When
            sut.writeChunk(Map.of("ts", data));
        }

        // Then
        long[] decoded = readLongColumn(file, "ts");
        assertThat(decoded).containsExactly(data);
    }

    static Stream<long[]> pcoSequentialI64ArrayProvider() {
        // Sequential arrays exercise the Consecutive delta path (stride-1 and stride-N)
        return Stream.of(
                LongStream.range(0, 500).toArray(),
                LongStream.range(-250, 250).toArray(),
                LongStream.range(0, 500).map(i -> i * 4).toArray(),
                LongStream.range(1_700_000_000_000L, 1_700_000_000_500L).toArray()
        );
    }

    /// PCO I64 consecutive delta: Java-encoded sequential arrays round-trip via Rust decoder.
    @ParameterizedTest
    @MethodSource("pcoSequentialI64ArrayProvider")
    void prop_pco_i64_consecutive_roundTripsViaRust(long[] data) throws IOException {
        Path tmp = Files.createTempDirectory("vortex-pbt-pco-i64");
        try {
            Path file = tmp.resolve("pbt_pco_i64.vtx");
            try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 var sut = VortexWriter.create(ch, TS_SCHEMA, WriteOptions.defaults(),
                         List.of(new PcoEncodingEncoder()))) {
                sut.writeChunk(Map.of("ts", data));
            }
            long[] decoded = readLongColumn(file, "ts");
            assertThat(decoded).containsExactly(data);
        } finally {
            deleteDir(tmp);
        }
    }

    private static UUID uuidFromBytes(byte[] b) {
        long msb = 0;
        long lsb = 0;
        for (int k = 0; k < 8; k++) {
            msb = (msb << 8) | (b[k] & 0xFFL);
            lsb = (lsb << 8) | (b[8 + k] & 0xFFL);
        }
        return new UUID(msb, lsb);
    }
}
