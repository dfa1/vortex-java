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
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.core.array.Float16Array;
import io.github.dfa1.vortex.core.array.LongArray;
import io.github.dfa1.vortex.encoding.EncodingRegistry;
import io.github.dfa1.vortex.io.VortexReader;
import io.github.dfa1.vortex.scan.ScanResult;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.Float2Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.ValueLayout;
import java.net.URI;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Cross-compatibility: Rust (JNI) writer → Java reader.
class RustWritesJavaReadsIntegrationTest {

    private static final String S3_BASE = "https://vortex-compat-fixtures.s3.amazonaws.com/v0.72.0/arrays/";

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
        try (VortexWriter writer = VortexWriter.create(SESSION, uri, JNI_SCHEMA, new HashMap<>(), ALLOCATOR)) {
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

    private static List<ScanResult> scanAll(VortexReader vf) {
        return scanAll(vf, io.github.dfa1.vortex.scan.ScanOptions.all());
    }

    private static List<ScanResult> scanAll(VortexReader vf,
            io.github.dfa1.vortex.scan.ScanOptions opts) {
        var results = new ArrayList<ScanResult>();
        var iter = vf.scan(opts);
        while (iter.hasNext()) {
            results.add(iter.next());
        }
        return results;
    }

    // ── JNI write helpers ─────────────────────────────────────────────────────

    private static long[] toLongs(ScanResult chunk) {
        var layout = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
        var arr = chunk.columns().get("id");
        long[] out = new long[(int) arr.length()];
        for (int i = 0; i < out.length; i++) {
            out[i] = arr.segment().get(layout, (long) i * Long.BYTES);
        }
        return out;
    }

    private static double[] toDoubles(ScanResult chunk) {
        var layout = ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
        var arr = chunk.columns().get("value");
        double[] out = new double[(int) arr.length()];
        for (int i = 0; i < out.length; i++) {
            out[i] = arr.segment().get(layout, (long) i * Double.BYTES);
        }
        return out;
    }

    // ── Java read helpers ─────────────────────────────────────────────────────

    private static String firstI64Column(Path file) throws IOException {
        try (var vf = VortexReader.open(file, EncodingRegistry.empty())) {
            if (vf.dtype() instanceof DType.Struct struct) {
                for (int i = 0; i < struct.fieldNames().size(); i++) {
                    if (struct.fieldTypes().get(i) instanceof DType.Primitive(PType pt, boolean _) && pt == PType.I64) {
                        return struct.fieldNames().get(i);
                    }
                }
            }
            throw new AssertionError("no I64 column found in " + file.getFileName());
        }
    }

    private static long[] readJniLongColumn(Path file, String column) throws IOException {
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

    private static long[] readJavaLongColumn(Path file, String column) throws IOException {
        try (var vf = VortexReader.open(file, EncodingRegistry.loadAll())) {
            var longs = new ArrayList<Long>();
            var iter = vf.scan(io.github.dfa1.vortex.scan.ScanOptions.columns(column));
            while (iter.hasNext()) {
                ScanResult r = iter.next();
                LongArray arr = (LongArray) r.columns().get(column);
                for (long i = 0; i < arr.length(); i++) {
                    longs.add(arr.getLong(i));
                }
            }
            return longs.stream().mapToLong(Long::longValue).toArray();
        }
    }

    private static Path downloadIfMissing(Path tmp, String name) throws Exception {
        Path cached = Path.of("/tmp/pco-fixtures", name);
        if (Files.exists(cached)) {
            return cached;
        }
        Path dest = tmp.resolve(name);
        try (var in = URI.create(S3_BASE + name).toURL().openStream()) {
            Files.copy(in, dest, StandardCopyOption.REPLACE_EXISTING);
        }
        return dest;
    }

    private static void assumeNetworkAvailable() {
        try {
            URI.create("https://vortex-compat-fixtures.s3.amazonaws.com").toURL().openStream().close();
        } catch (Exception e) {
            assumeTrue(false, "no network");
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
        try (var vf = VortexReader.open(file, EncodingRegistry.loadAll())) {
            List<ScanResult> results = scanAll(vf);
            assertThat(results).hasSize(1);
            assertThat(results.getFirst().rowCount()).isEqualTo(3L);
            assertThat(toLongs(results.getFirst())).containsExactly(1L, 2L, 3L);
            assertThat(toDoubles(results.getFirst())).containsExactly(1.1, 2.2, 3.3);
        }
    }

    @Test
    void jniWriter_javaReader_multipleChunks(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("jni_multi.vtx");
        String uri = file.toAbsolutePath().toUri().toString();
        try (VortexWriter writer = VortexWriter.create(SESSION, uri, JNI_SCHEMA, new HashMap<>(), ALLOCATOR)) {
            flushBatch(writer, new long[]{1L, 2L}, new double[]{1.1, 2.2});
            flushBatch(writer, new long[]{3L, 4L, 5L}, new double[]{3.3, 4.4, 5.5});
        }

        // When / Then — JNI may merge small batches; verify total rows and values
        try (var vf = VortexReader.open(file, EncodingRegistry.loadAll())) {
            List<ScanResult> results = scanAll(vf);
            long totalRows = results.stream().mapToLong(ScanResult::rowCount).sum();
            assertThat(totalRows).isEqualTo(5L);
            long[] allIds = results.stream()
                                    .flatMapToLong(r -> java.util.Arrays.stream(toLongs(r)))
                                    .toArray();
            assertThat(allIds).containsExactly(1L, 2L, 3L, 4L, 5L);
        }
    }

    @Test
    void jniWriter_javaReader_columnProjection(@TempDir Path tmp) throws IOException {
        // Given
        Path file = tmp.resolve("jni_proj.vtx");
        writeJni(file, new long[]{10L, 20L}, new double[]{0.1, 0.2});

        // When / Then
        try (var vf = VortexReader.open(file, EncodingRegistry.loadAll())) {
            List<ScanResult> results = scanAll(vf, io.github.dfa1.vortex.scan.ScanOptions.columns("id"));
            assertThat(results).hasSize(1);
            assertThat(results.getFirst().columns()).containsKey("id");
            assertThat(results.getFirst().columns()).doesNotContainKey("value");
            assertThat(toLongs(results.getFirst())).containsExactly(10L, 20L);
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
        try (var vf = VortexReader.open(file, EncodingRegistry.loadAll())) {
            List<ScanResult> results = scanAll(vf, io.github.dfa1.vortex.scan.ScanOptions.columns("value"));
            long total = results.stream().mapToLong(ScanResult::rowCount).sum();
            assertThat(total).isEqualTo(n);
            var layout = ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
            double sum = 0;
            double[] first9 = new double[9];
            int spotIdx = 0;
            for (ScanResult r : results) {
                var arr = r.columns().get("value");
                for (long j = 0; j < arr.length(); j++) {
                    double v = arr.segment().get(layout, j * Double.BYTES);
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

    // ── S3 helpers ────────────────────────────────────────────────────────────

    @Test
    void jniWriter_nullableColumn_decodesWithoutError(@TempDir Path tmp) throws IOException {
        // Given — nullable I64 column; the JNI compressor encodes this as fastlanes.bitpacked
        // (not vortex.masked) since the compressor folds validity into the encoding.
        // This test verifies end-to-end decoding of nullable schemas does not throw.
        Path file = tmp.resolve("nullable.vtx");
        String uri = file.toAbsolutePath().toUri().toString();
        int n = 10_000;
        try (VortexWriter writer = VortexWriter.create(SESSION, uri, NULLABLE_SCHEMA, new HashMap<>(), ALLOCATOR)) {
            try (VectorSchemaRoot root = VectorSchemaRoot.create(NULLABLE_SCHEMA, ALLOCATOR)) {
                BigIntVector idVec = (BigIntVector) root.getVector("id");
                idVec.allocateNew(n);
                for (int i = 0; i < n; i++) {
                    if (i % 5 == 0) {
                        idVec.setNull(i);
                    } else {
                        idVec.setSafe(i, (long) i);
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
        try (var vf = VortexReader.open(file, EncodingRegistry.loadAll())) {
            List<ScanResult> results = scanAll(vf);
            long totalRows = results.stream().mapToLong(ScanResult::rowCount).sum();
            assertThat(totalRows).isEqualTo(n);
            assertThat(vf.dtype()).isInstanceOf(DType.Struct.class);
            DType colDtype = ((DType.Struct) vf.dtype()).field("id");
            assertThat(colDtype.nullable()).isTrue();
            // null slots store 0 (bitpacked folds validity); non-null slot i stores i
            // sum(i for i in [0,10000) if i%5!=0) = 49995000 - 5*(0+5+…+9995) = 40000000
            // if proto tag drifts, all values decode as 0 and sum would be 0
            var longLayout = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
            long sum = 0;
            for (ScanResult r : results) {
                var arr = r.columns().get("id");
                for (long j = 0; j < arr.length(); j++) {
                    sum += arr.segment().get(longLayout, j * Long.BYTES);
                }
            }
            assertThat(sum).isEqualTo(40_000_000L);
        }
    }

    @Test
    void s3_pcoVortex_javaDecodeMatchesJni(@TempDir Path tmp) throws Exception {
        // Given — pco.vortex: synthetic file with all pco ptypes; Classic+Consecutive+IntMult+FloatMult modes
        assumeNetworkAvailable();
        Path file = downloadIfMissing(tmp, "pco.vortex");
        String col = firstI64Column(file);

        // When
        long[] jni = readJniLongColumn(file, col);
        long[] java = readJavaLongColumn(file, col);

        // Then — same values (order may differ across chunks)
        Arrays.sort(jni);
        Arrays.sort(java);
        assertThat(java).containsExactly(jni);
    }

    @Test
    void s3_tpchLineitem_javaDecodeMatchesJni(@TempDir Path tmp) throws Exception {
        // Given — tpch_lineitem.compact.vortex: I64/I32/Decimal/date columns, Classic+Consecutive
        assumeNetworkAvailable();
        Path file = downloadIfMissing(tmp, "tpch_lineitem.compact.vortex");
        String col = firstI64Column(file);

        // When
        long[] jni = readJniLongColumn(file, col);
        long[] java = readJavaLongColumn(file, col);

        // Then
        Arrays.sort(jni);
        Arrays.sort(java);
        assertThat(java).containsExactly(jni);
    }

    @Test
    void s3_tpchOrders_javaDecodeMatchesJni(@TempDir Path tmp) throws Exception {
        // Given — tpch_orders.compact.vortex: I64/I32/Decimal/date columns, Classic+Consecutive
        assumeNetworkAvailable();
        Path file = downloadIfMissing(tmp, "tpch_orders.compact.vortex");
        String col = firstI64Column(file);

        // When
        long[] jni = readJniLongColumn(file, col);
        long[] java = readJavaLongColumn(file, col);

        // Then
        Arrays.sort(jni);
        Arrays.sort(java);
        assertThat(java).containsExactly(jni);
    }

    @Test
    void s3_clickbenchHits5k_javaDecodeMatchesJni(@TempDir Path tmp) throws Exception {
        // Given — clickbench_hits_5k.compact.vortex: I16/I32/I64/ts-I64 columns, Classic+Consecutive
        assumeNetworkAvailable();
        Path file = downloadIfMissing(tmp, "clickbench_hits_5k.compact.vortex");
        String col = firstI64Column(file);

        // When
        long[] jni = readJniLongColumn(file, col);
        long[] java = readJavaLongColumn(file, col);

        // Then
        Arrays.sort(jni);
        Arrays.sort(java);
        assertThat(java).containsExactly(jni);
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
        try (VortexWriter writer = VortexWriter.create(SESSION, uri, F16_SCHEMA, new HashMap<>(), ALLOCATOR)) {
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
        try (var vf = VortexReader.open(file, EncodingRegistry.loadAll())) {
            List<ScanResult> results = scanAll(vf);

            // Then — correct dtype, correct values
            assertThat(vf.dtype()).isInstanceOf(DType.Struct.class);
            assertThat(((DType.Struct) vf.dtype()).field("v"))
                    .isEqualTo(new DType.Primitive(PType.F16, false));
            assertThat(results).hasSize(1);
            Float16Array arr = (Float16Array) results.getFirst().columns().get("v");
            assertThat(arr.length()).isEqualTo(f16bits.length);
            for (int i = 0; i < f16bits.length; i++) {
                assertThat(arr.getFloat(i)).as("index %d", i).isEqualTo(Float.float16ToFloat(f16bits[i]));
            }
        }
    }
}
