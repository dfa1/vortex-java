package io.github.dfa1.vortex.integration;

import dev.vortex.api.Session;
import dev.vortex.api.VortexWriter;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.inspect.VortexInspector;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/// Ground-truth interop for `fastlanes.rle` over floating-point columns (issue #209).
///
/// The Rust (JNI) writer compresses long constant runs of doubles to
/// `fastlanes.rle`; before the fix the Java reader threw
/// `fastlanes.rle: unsupported ptype F64`. These tests write such a column with
/// the JNI writer, then confirm via [VortexInspector] that RLE was actually
/// chosen ( `assumeTrue` — a test asserting on whatever encoding the compressor
/// happened to pick would be meaningless), and finally assert the Java reader
/// decodes every value exactly. The nullable case additionally exercises the
/// validity re-wrap the F64 arm shares with the integer arms.
class RleF64InteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();

    private static final Schema F64_SCHEMA = new Schema(List.of(
            Field.notNullable("v", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE))
    ));
    private static final Schema F64_NULLABLE_SCHEMA = new Schema(List.of(
            Field.nullable("v", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE))
    ));
    private static final Schema F32_SCHEMA = new Schema(List.of(
            Field.notNullable("v", new ArrowType.FloatingPoint(FloatingPointPrecision.SINGLE))
    ));

    /// Number of rows: long constant runs (512-row blocks of one value) over a
    /// handful of distinct values is the canonical RLE-friendly shape and matches
    /// the real-world seoul weather columns from #209.
    private static final int ROWS = 8_192;
    private static final int RUN = 512;

    static {
        NativeLoader.loadJni();
    }

    @Test
    void jniWriter_javaReader_f64RunLengthEncoded(@TempDir Path tmp) throws IOException {
        // Given — 8_192 doubles in 512-row constant runs cycling 5 distinct fractional
        // values; fractional values catch any accidental integer-narrowing decode bug.
        double[] unique = {-1.5, 2.25, 3.75, -4.125, 5.5};
        double[] vals = new double[ROWS];
        for (int i = 0; i < ROWS; i++) {
            vals[i] = unique[(i / RUN) % unique.length];
        }
        Path file = tmp.resolve("rle_f64.vtx");
        writeF64(file, vals);
        assumeRle(file);

        // When — Java reader decodes the whole column
        double[] result = readDoubleColumn(file);

        // Then — every value matches the input exactly
        assertThat(result).containsExactly(vals);
    }

    @Test
    void jniWriter_javaReader_f64NullableDecodesValues(@TempDir Path tmp) throws IOException {
        // Given — the same run shape but nullable, with whole 512-row runs set null.
        // Exercises the F64 RLE decode path on a nullable schema end-to-end.
        double[] unique = {10.5, 20.25, 30.75};
        double[] vals = new double[ROWS];
        boolean[] nulls = new boolean[ROWS];
        for (int i = 0; i < ROWS; i++) {
            int block = i / RUN;
            nulls[i] = block % 3 == 1;           // every third run is entirely null
            vals[i] = unique[block % unique.length];
        }
        Path file = tmp.resolve("rle_f64_nullable.vtx");
        writeF64Nullable(file, vals, nulls);
        assumeRle(file);

        // When — decode the whole column (unwrapping any validity mask)
        double[] result = readDoubleColumnUnwrapped(file);

        // Then — decode does not throw, row count is right, and every non-null input
        // value is exact. This test deliberately does not assert the null mask: the JNI
        // writer folds RLE validity into the indices child, and surfacing it as a
        // MaskedArray from the RLE decode path is the subject of the separate in-flight
        // fix #210 (the existing jniWriter_nullableColumn_decodesWithoutError follows the
        // same convention for the bitpacked case).
        assertThat(result).hasSize(ROWS);
        for (int i = 0; i < ROWS; i++) {
            if (!nulls[i]) {
                assertThat(result[i]).as("value@%d", i).isEqualTo(vals[i]);
            }
        }
    }

    @Test
    void jniWriter_javaReader_f32RunLengthEncoded(@TempDir Path tmp) throws IOException {
        // Given — the same run shape as F64 but 32-bit; guards the 4-byte value read.
        // Gated on RLE selection: the compressor may prefer another encoding for F32,
        // in which case the test is skipped rather than asserting on the wrong path.
        float[] unique = {-1.5f, 2.25f, 3.75f, -4.125f, 5.5f};
        float[] vals = new float[ROWS];
        for (int i = 0; i < ROWS; i++) {
            vals[i] = unique[(i / RUN) % unique.length];
        }
        Path file = tmp.resolve("rle_f32.vtx");
        writeF32(file, vals);
        assumeRle(file);

        // When
        float[] result = readFloatColumn(file);

        // Then
        assertThat(result).containsExactly(vals);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /// Skips the test unless the JNI compressor actually chose `fastlanes.rle`.
    private static void assumeRle(Path file) throws IOException {
        String report;
        try (VortexReader vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            report = VortexInspector.inspect(vf);
        }
        assumeTrue(report.contains("fastlanes.rle"),
                () -> "compressor did not choose fastlanes.rle:\n" + report);
    }

    private static void writeF64(Path file, double[] vals) throws IOException {
        String uri = file.toAbsolutePath().toUri().toString();
        try (VortexWriter writer = VortexWriter.create(SESSION, uri, F64_SCHEMA, new HashMap<>(), ALLOCATOR);
             VectorSchemaRoot root = VectorSchemaRoot.create(F64_SCHEMA, ALLOCATOR)) {
            Float8Vector vec = (Float8Vector) root.getVector("v");
            vec.allocateNew(vals.length);
            for (int i = 0; i < vals.length; i++) {
                vec.setSafe(i, vals[i]);
            }
            root.setRowCount(vals.length);
            exportBatch(writer, root);
        }
    }

    private static void writeF64Nullable(Path file, double[] vals, boolean[] nulls) throws IOException {
        String uri = file.toAbsolutePath().toUri().toString();
        try (VortexWriter writer = VortexWriter.create(SESSION, uri, F64_NULLABLE_SCHEMA, new HashMap<>(), ALLOCATOR);
             VectorSchemaRoot root = VectorSchemaRoot.create(F64_NULLABLE_SCHEMA, ALLOCATOR)) {
            Float8Vector vec = (Float8Vector) root.getVector("v");
            vec.allocateNew(vals.length);
            for (int i = 0; i < vals.length; i++) {
                if (nulls[i]) {
                    vec.setNull(i);
                } else {
                    vec.setSafe(i, vals[i]);
                }
            }
            root.setRowCount(vals.length);
            exportBatch(writer, root);
        }
    }

    private static void writeF32(Path file, float[] vals) throws IOException {
        String uri = file.toAbsolutePath().toUri().toString();
        try (VortexWriter writer = VortexWriter.create(SESSION, uri, F32_SCHEMA, new HashMap<>(), ALLOCATOR);
             VectorSchemaRoot root = VectorSchemaRoot.create(F32_SCHEMA, ALLOCATOR)) {
            Float4Vector vec = (Float4Vector) root.getVector("v");
            vec.allocateNew(vals.length);
            for (int i = 0; i < vals.length; i++) {
                vec.setSafe(i, vals[i]);
            }
            root.setRowCount(vals.length);
            exportBatch(writer, root);
        }
    }

    private static void exportBatch(VortexWriter writer, VectorSchemaRoot root) throws IOException {
        try (ArrowArray arr = ArrowArray.allocateNew(ALLOCATOR);
             ArrowSchema schema = ArrowSchema.allocateNew(ALLOCATOR)) {
            Data.exportVectorSchemaRoot(ALLOCATOR, root, null, arr, schema);
            writer.writeBatch(arr.memoryAddress(), schema.memoryAddress());
        }
    }

    private static double[] readDoubleColumn(Path file) throws IOException {
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll());
             var iter = vf.scan(ScanOptions.columns("v"))) {
            var out = new ArrayList<Double>();
            iter.forEachRemaining(c -> {
                DoubleArray arr = c.column("v");
                for (long i = 0; i < arr.length(); i++) {
                    out.add(arr.getDouble(i));
                }
            });
            return out.stream().mapToDouble(Double::doubleValue).toArray();
        }
    }

    private static double[] readDoubleColumnUnwrapped(Path file) throws IOException {
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll());
             var iter = vf.scan(ScanOptions.columns("v"))) {
            var out = new ArrayList<Double>();
            iter.forEachRemaining(c -> {
                Array a = c.column("v");
                DoubleArray arr = (DoubleArray) (a instanceof MaskedArray m ? m.inner() : a);
                for (long i = 0; i < arr.length(); i++) {
                    out.add(arr.getDouble(i));
                }
            });
            return out.stream().mapToDouble(Double::doubleValue).toArray();
        }
    }

    private static float[] readFloatColumn(Path file) throws IOException {
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll());
             var iter = vf.scan(ScanOptions.columns("v"))) {
            var out = new ArrayList<Float>();
            iter.forEachRemaining(c -> {
                FloatArray arr = c.column("v");
                for (long i = 0; i < arr.length(); i++) {
                    out.add(arr.getFloat(i));
                }
            });
            float[] result = new float[out.size()];
            for (int i = 0; i < result.length; i++) {
                result[i] = out.get(i);
            }
            return result;
        }
    }
}
