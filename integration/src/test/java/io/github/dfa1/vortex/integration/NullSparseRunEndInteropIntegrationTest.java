package io.github.dfa1.vortex.integration;

import dev.vortex.api.Session;
import dev.vortex.api.VortexWriter;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.inspect.InspectorTree;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.NullArray;
import io.github.dfa1.vortex.reader.array.ShortArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.reader.array.VarBinSparseArray;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.SmallIntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Random;
import java.util.function.ObjIntConsumer;

import static org.assertj.core.api.Assertions.assertThat;

/// Gate-running interop cover for null-fill Sparse (#226), null-run RunEnd (#225), and
/// null-scalar Constant (#246) validity.
///
/// These fixes are exercised by the weekly Raincloud conformance corpus only; this test
/// reproduces each shape on every `verify` run from a JNI-written (Rust reference) file,
/// so a validity regression reddens the per-PR gate rather than surfacing a week later.
///
/// The bundled `vortex-jni` compressor is version-pinned, so its encoding choice for a crafted
/// input is deterministic — verified by probing: a mostly-null column with a small fraction of
/// scattered non-`ALP`-able values compresses to `vortex.sparse` with a null fill scalar (for
/// utf8 too, not only for primitives), and a column of distinct value runs interleaved with null
/// runs compresses to `vortex.runend` with null run-values. Every assertion below therefore
/// hard-checks the chosen encoding; a future JNI bump that changes the choice is a deliberate
/// change that should refresh this fixture.
class NullSparseRunEndInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final int ROWS = 5000;

    static {
        NativeLoader.loadJni();
    }

    @Test
    void jniNullFillSparse_f64_nullRowsDecodeNull(@TempDir Path tmp) throws IOException {
        // Given — a nullable f64 column that is ~98% null with scattered random (non-ALP-able)
        // values. This is the world-energy `biofuel_cons_change_pct` shape: the JNI compressor
        // picks vortex.sparse with a null fill, so every unpatched row must decode as null (#226).
        Schema schema = new Schema(List.of(
                Field.nullable("v", new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE))));
        Random rng = new Random(226);
        Double[] expected = new Double[ROWS];
        Path file = tmp.resolve("null_fill_sparse_f64.vtx");
        writeJni(file, schema, (root, i) -> {
            Float8Vector vec = (Float8Vector) root.getVector("v");
            if (i % 50 == 0) {
                double value = rng.nextDouble() * 1e9;
                expected[i] = value;
                vec.setSafe(i, value);
            } else {
                vec.setNull(i);
            }
        });

        // When
        List<Double> result = readF64(file);

        // Then — encoding is null-fill sparse, and every row round-trips value-for-value
        // (null in ⇒ null out, value in ⇒ value out).
        assertThat(usedEncodings(file)).contains("vortex.sparse");
        assertThat(result).containsExactly(expected);
    }

    @Test
    void jniNullFillSparse_i64_nullRowsDecodeNull(@TempDir Path tmp) throws IOException {
        // Given — the nuclear_share_energy shape: a nullable i64 column, ~99% null with a few
        // scattered random values; the JNI compressor picks vortex.sparse with a null fill.
        Schema schema = new Schema(List.of(Field.nullable("v", new ArrowType.Int(64, true))));
        Random rng = new Random(2266);
        Long[] expected = new Long[ROWS];
        Path file = tmp.resolve("null_fill_sparse_i64.vtx");
        writeJni(file, schema, (root, i) -> {
            BigIntVector vec = (BigIntVector) root.getVector("v");
            if (i % 97 == 0) {
                long value = rng.nextLong();
                expected[i] = value;
                vec.setSafe(i, value);
            } else {
                vec.setNull(i);
            }
        });

        // When
        List<Long> result = readI64(file);

        // Then
        assertThat(usedEncodings(file)).contains("vortex.sparse");
        assertThat(result).containsExactly(expected);
    }

    /// The utf8 sibling of the two null-fill sparse cases above, and the ground-truth cover for
    /// the lazy `VarBinSparseArray` carrier (#340) — before it, this shape merged every patch
    /// into a full `length`-row bytes buffer, and the fill was dropped on the way in.
    ///
    /// Only the null-fill half is reachable from here: probing the pinned JNI compressor with a
    /// utf8 column that is mostly one NON-null value gets `vortex.dict` (or `vortex.fsst` over
    /// `vortex.runend` for a mostly-empty-string column), never sparse with a non-null string
    /// fill. So the non-null-fill fix stays unit-covered only until a corpus file or a JNI bump
    /// produces that shape.
    @Test
    void jniNullFillSparse_utf8_nullRowsDecodeNull(@TempDir Path tmp) throws IOException {
        // Given — a nullable utf8 column, ~99% null with a few scattered distinct strings.
        Schema schema = new Schema(List.of(Field.nullable("v", new ArrowType.Utf8())));
        String[] expected = new String[ROWS];
        Path file = tmp.resolve("null_fill_sparse_utf8.vtx");
        writeJni(file, schema, (root, i) -> {
            VarCharVector vec = (VarCharVector) root.getVector("v");
            if (i % 97 == 0) {
                String value = "x" + i;
                expected[i] = value;
                vec.setSafe(i, value.getBytes(StandardCharsets.UTF_8));
            } else {
                vec.setNull(i);
            }
        });

        // When
        List<String> result = readUtf8(file);

        // Then — the column decodes through the lazy sparse carrier, and every row round-trips.
        assertThat(usedEncodings(file)).contains("vortex.sparse");
        assertThat(carrier(file)).isEqualTo(VarBinSparseArray.class);
        assertThat(result).containsExactly(expected);
    }

    @Test
    void jniNullRunRunEnd_i16_nullRowsDecodeNull(@TempDir Path tmp) throws IOException {
        // Given — the uci-online-retail `customerid` u16? shape: runs of a distinct value per
        // block interleaved with whole null runs. The JNI compressor picks vortex.runend; before
        // #225 a null run expanded to the run's filler value instead of null.
        Schema schema = new Schema(List.of(Field.nullable("v", new ArrowType.Int(16, true))));
        Short[] expected = new Short[ROWS];
        Path file = tmp.resolve("null_run_runend_i16.vtx");
        writeJni(file, schema, (root, i) -> {
            SmallIntVector vec = (SmallIntVector) root.getVector("v");
            int block = i / 100;
            if (block % 4 == 0) {
                vec.setNull(i);
            } else {
                short value = (short) (1000 + block);
                expected[i] = value;
                vec.setSafe(i, value);
            }
        });

        // When
        List<Short> result = readI16(file);

        // Then — encoding is run-end, and null runs decode as null (not the run filler).
        assertThat(usedEncodings(file)).contains("vortex.runend");
        assertThat(result).containsExactly(expected);
    }

    @Test
    void jniNullRunRunEnd_i64_nullRowsDecodeNull(@TempDir Path tmp) throws IOException {
        // Given — a nullable i64 column of distinct value runs interleaved with null runs; the
        // JNI compressor picks vortex.runend with nullable run-values (#225).
        Schema schema = new Schema(List.of(Field.nullable("v", new ArrowType.Int(64, true))));
        Long[] expected = new Long[ROWS];
        Path file = tmp.resolve("null_run_runend_i64.vtx");
        writeJni(file, schema, (root, i) -> {
            BigIntVector vec = (BigIntVector) root.getVector("v");
            int block = i / 200;
            if (block % 3 == 0) {
                vec.setNull(i);
            } else {
                long value = 100_000L + block;
                expected[i] = value;
                vec.setSafe(i, value);
            }
        });

        // When
        List<Long> result = readI64(file);

        // Then
        assertThat(usedEncodings(file)).contains("vortex.runend");
        assertThat(result).containsExactly(expected);
    }

    @Test
    void jniNullConstant_i64_allRowsDecodeNull(@TempDir Path tmp) throws IOException {
        // Given — a nullable i64 column where every row is null. The JNI compressor encodes
        // this as vortex.constant with a null scalar; before #246 the decoder produced
        // LazyConstantLongArray(value=0) — all rows read back as 0 rather than null.
        Schema schema = new Schema(List.of(Field.nullable("v", new ArrowType.Int(64, true))));
        Path file = tmp.resolve("null_constant_i64.vtx");
        writeJni(file, schema, (root, i) -> { /* no setSafe calls — every row stays null */ });

        // When
        long[] rowCount = {0};
        long[] nullCount = {0};
        try (VortexReader reader = VortexReader.open(file, ReadRegistry.loadAll());
                var iter = reader.scan(ScanOptions.all())) {
            iter.forEachRemaining(chunk -> {
                Array col = chunk.column("v");
                rowCount[0] += col.length();
                if (col instanceof NullArray) {
                    nullCount[0] += col.length();
                } else if (col instanceof MaskedArray m) {
                    for (long i = 0; i < m.length(); i++) {
                        if (!m.isValid(i)) {
                            nullCount[0]++;
                        }
                    }
                }
            });
        }

        // Then — every row must be null; a regressed decoder returning LazyConstantLongArray(0)
        // would give nullCount=0 and expose the corruption
        assertThat(rowCount[0]).isEqualTo(ROWS);
        assertThat(nullCount[0])
                .as("all %d rows must decode as null", ROWS)
                .isEqualTo(ROWS);
        assertThat(usedEncodings(file)).contains("vortex.constant");
    }

    // ── JNI write helper ──────────────────────────────────────────────────────

    private static void writeJni(Path file, Schema schema, ObjIntConsumer<VectorSchemaRoot> fill) throws IOException {
        String uri = file.toAbsolutePath().toUri().toString();
        try (VortexWriter writer = VortexWriter.create(SESSION, uri, schema, new HashMap<>(), ALLOCATOR);
             VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
            for (var vec : root.getFieldVectors()) {
                vec.setInitialCapacity(ROWS);
                vec.allocateNew();
            }
            for (int i = 0; i < ROWS; i++) {
                fill.accept(root, i);
            }
            root.setRowCount(ROWS);
            try (ArrowArray arr = ArrowArray.allocateNew(ALLOCATOR);
                 ArrowSchema as = ArrowSchema.allocateNew(ALLOCATOR)) {
                Data.exportVectorSchemaRoot(ALLOCATOR, root, null, arr, as);
                writer.writeBatch(arr.memoryAddress(), as.memoryAddress());
            }
        }
    }

    // ── Java read helpers ─────────────────────────────────────────────────────

    private static List<String> usedEncodings(Path file) throws IOException {
        try (VortexReader reader = VortexReader.open(file, ReadRegistry.loadAll())) {
            return new ArrayList<>(InspectorTree.build(reader).usedEncodings());
        }
    }

    private static List<Double> readF64(Path file) throws IOException {
        var out = new ArrayList<Double>();
        forEachValue(file, (masked, inner, i) -> out.add(masked.isValid(i) ? ((DoubleArray) inner).getDouble(i) : null));
        return out;
    }

    private static List<Long> readI64(Path file) throws IOException {
        var out = new ArrayList<Long>();
        forEachValue(file, (masked, inner, i) -> out.add(masked.isValid(i) ? ((LongArray) inner).getLong(i) : null));
        return out;
    }

    private static List<String> readUtf8(Path file) throws IOException {
        var out = new ArrayList<String>();
        forEachValue(file, (masked, inner, i) -> out.add(masked.isValid(i) ? ((VarBinArray) inner).getString(i) : null));
        return out;
    }

    /// Returns the concrete [Array] class the single column's payload decodes to, so a test can
    /// assert the decode path taken and not only the values it produced — the eager and lazy
    /// paths agree on every value here, so values alone would pass on both.
    ///
    /// @param file the Vortex file to scan
    /// @return the runtime class of the first chunk's unwrapped payload array
    /// @throws IOException if the file cannot be opened or scanned
    private static Class<?> carrier(Path file) throws IOException {
        try (VortexReader reader = VortexReader.open(file, ReadRegistry.loadAll());
             var iter = reader.scan(ScanOptions.all())) {
            MaskedArray masked = iter.next().column("v");
            return masked.inner().getClass();
        }
    }

    private static List<Short> readI16(Path file) throws IOException {
        var out = new ArrayList<Short>();
        forEachValue(file, (masked, inner, i) -> out.add(masked.isValid(i) ? ((ShortArray) inner).getShort(i) : null));
        return out;
    }

    /// Scans the single-column `file` and hands each row's validity view, the unwrapped inner
    /// payload [Array], and the row index to `consumer`. A nullable column decodes to a
    /// [MaskedArray], so this centralizes the unwrap the value readers share.
    ///
    /// @param file     the Vortex file to scan
    /// @param consumer receiver of `(maskedColumn, innerPayload, rowIndex)` per row
    /// @throws IOException if the file cannot be opened or scanned
    private static void forEachValue(Path file, RowConsumer consumer) throws IOException {
        try (VortexReader reader = VortexReader.open(file, ReadRegistry.loadAll());
             var iter = reader.scan(ScanOptions.all())) {
            iter.forEachRemaining(chunk -> {
                MaskedArray masked = chunk.column("v");
                Array inner = masked.inner();
                for (long i = 0; i < masked.length(); i++) {
                    consumer.accept(masked, inner, i);
                }
            });
        }
    }

    /// Per-row callback used by [#forEachValue(Path, RowConsumer)].
    @FunctionalInterface
    private interface RowConsumer {
        /// Consumes one decoded row.
        ///
        /// @param masked the nullable column as a [MaskedArray] for validity checks
        /// @param inner  the unwrapped non-nullable payload [Array]
        /// @param index  the row index within the current chunk
        void accept(MaskedArray masked, Array inner, long index);
    }
}
