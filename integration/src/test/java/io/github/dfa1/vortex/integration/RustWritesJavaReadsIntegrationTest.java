package io.github.dfa1.vortex.integration;

import dev.vortex.api.Session;
import dev.vortex.api.VortexWriter;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.encoding.DecoderRegistry;
import io.github.dfa1.vortex.io.VortexFile;
import io.github.dfa1.vortex.scan.ScanResult;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/// Cross-compatibility: Rust (JNI) writer → Java reader.
class RustWritesJavaReadsIntegrationTest {

    static {
        NativeLoader.loadJni();
    }

    private static final Session         SESSION   = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();

    private static final Schema JNI_SCHEMA = new Schema(List.of(
        Field.notNullable("id",    new ArrowType.Int(64, true)),
        Field.notNullable("value", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE))
    ));

    @Test
    void jniWriter_javaReader_singleChunk(@TempDir Path tmp) throws IOException {
        // Given
        Path     file = tmp.resolve("jni_single.vtx");
        long[]   ids  = {1L, 2L, 3L};
        double[] vals = {1.1, 2.2, 3.3};
        writeJni(file, ids, vals);

        // When / Then
        try (var vf = VortexFile.open(file, DecoderRegistry.loadAll())) {
            List<ScanResult> results = scanAll(vf);
            assertThat(results).hasSize(1);
            assertThat(results.get(0).rowCount()).isEqualTo(3L);
            assertThat(toLongs(results.get(0))).containsExactly(1L, 2L, 3L);
            assertThat(toDoubles(results.get(0))).containsExactly(1.1, 2.2, 3.3);
        }
    }

    @Test
    void jniWriter_javaReader_multipleChunks(@TempDir Path tmp) throws IOException {
        // Given
        Path   file = tmp.resolve("jni_multi.vtx");
        String uri  = file.toAbsolutePath().toUri().toString();
        try (VortexWriter writer = VortexWriter.create(SESSION, uri, JNI_SCHEMA, new HashMap<>(), ALLOCATOR)) {
            flushBatch(writer, new long[]{1L, 2L}, new double[]{1.1, 2.2});
            flushBatch(writer, new long[]{3L, 4L, 5L}, new double[]{3.3, 4.4, 5.5});
        }

        // When / Then — JNI may merge small batches; verify total rows and values
        try (var vf = VortexFile.open(file, DecoderRegistry.loadAll())) {
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
        try (var vf = VortexFile.open(file, DecoderRegistry.loadAll())) {
            List<ScanResult> results = scanAll(vf, io.github.dfa1.vortex.scan.ScanOptions.columns("id"));
            assertThat(results).hasSize(1);
            assertThat(results.get(0).columns()).containsKey("id");
            assertThat(results.get(0).columns()).doesNotContainKey("value");
            assertThat(toLongs(results.get(0))).containsExactly(10L, 20L);
        }
    }

    @Test
    void jniWriter_javaReader_fewUniqueF64Values(@TempDir Path tmp) throws IOException {
        // Given — 10_000 rows cycling through only 3 unique F64 values to trigger dict encoding
        int n = 10_000;
        long[]   ids  = new long[n];
        double[] vals = new double[n];
        double[] unique = {1.1, 2.2, 3.3};
        for (int i = 0; i < n; i++) {
            ids[i]  = i;
            vals[i] = unique[i % unique.length];
        }
        Path file = tmp.resolve("jni_dict.vtx");
        writeJni(file, ids, vals);

        // When / Then
        try (var vf = VortexFile.open(file, DecoderRegistry.loadAll())) {
            List<ScanResult> results = scanAll(vf, io.github.dfa1.vortex.scan.ScanOptions.columns("value"));
            long total = results.stream().mapToLong(ScanResult::rowCount).sum();
            assertThat(total).isEqualTo(n);
            var layout = ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
            double sum = 0;
            for (ScanResult r : results) {
                var arr = r.columns().get("value");
                for (long j = 0; j < arr.length(); j++) {
                    sum += arr.buffer(0).get(layout, j * Double.BYTES);
                }
            }
            // 10_000 rows: 3333 full cycles of [1.1,2.2,3.3] (=6.6 each) + one 1.1 remainder
            assertThat(sum).isCloseTo(21_998.9, org.assertj.core.data.Offset.offset(0.1));
        }
    }

    // ── JNI write helpers ─────────────────────────────────────────────────────

    private static void writeJni(Path file, long[] ids, double[] vals) throws IOException {
        String uri = file.toAbsolutePath().toUri().toString();
        try (VortexWriter writer = VortexWriter.create(SESSION, uri, JNI_SCHEMA, new HashMap<>(), ALLOCATOR)) {
            flushBatch(writer, ids, vals);
        }
    }

    private static void flushBatch(VortexWriter writer, long[] ids, double[] vals) throws IOException {
        int n = ids.length;
        try (VectorSchemaRoot root = VectorSchemaRoot.create(JNI_SCHEMA, ALLOCATOR)) {
            BigIntVector idVec  = (BigIntVector) root.getVector("id");
            Float8Vector valVec = (Float8Vector) root.getVector("value");
            idVec.allocateNew(n);
            valVec.allocateNew(n);
            for (int i = 0; i < n; i++) {
                idVec.setSafe(i, ids[i]);
                valVec.setSafe(i, vals[i]);
            }
            root.setRowCount(n);
            try (ArrowArray  arr    = ArrowArray.allocateNew(ALLOCATOR);
                 ArrowSchema schema = ArrowSchema.allocateNew(ALLOCATOR)) {
                Data.exportVectorSchemaRoot(ALLOCATOR, root, null, arr, schema);
                writer.writeBatch(arr.memoryAddress(), schema.memoryAddress());
            }
        }
    }

    // ── Java read helpers ─────────────────────────────────────────────────────

    private static List<ScanResult> scanAll(VortexFile vf) throws IOException {
        return scanAll(vf, io.github.dfa1.vortex.scan.ScanOptions.all());
    }

    private static List<ScanResult> scanAll(VortexFile vf,
                                             io.github.dfa1.vortex.scan.ScanOptions opts) throws IOException {
        var results = new ArrayList<ScanResult>();
        var iter    = vf.scan(opts);
        while (iter.hasNext()) {
            results.add(iter.next());
        }
        return results;
    }

    private static long[] toLongs(ScanResult chunk) {
        var layout = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
        var arr    = chunk.columns().get("id");
        long[] out = new long[(int) arr.length()];
        for (int i = 0; i < out.length; i++) {
            out[i] = arr.buffer(0).get(layout, (long) i * Long.BYTES);
        }
        return out;
    }

    private static double[] toDoubles(ScanResult chunk) {
        var layout = ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);
        var arr    = chunk.columns().get("value");
        double[] out = new double[(int) arr.length()];
        for (int i = 0; i < out.length; i++) {
            out[i] = arr.buffer(0).get(layout, (long) i * Double.BYTES);
        }
        return out;
    }
}
