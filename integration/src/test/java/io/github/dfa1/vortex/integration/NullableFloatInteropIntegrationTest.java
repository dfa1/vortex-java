package io.github.dfa1.vortex.integration;

import dev.vortex.api.DataSource;
import dev.vortex.api.Expression;
import dev.vortex.api.ImmutableScanOptions;
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
import io.github.dfa1.vortex.writer.WriteOptions;
import io.github.dfa1.vortex.writer.encode.NullableData;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float4Vector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.apache.arrow.vector.types.FloatingPointPrecision;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.apache.arrow.vector.types.pojo.Field;
import org.apache.arrow.vector.types.pojo.Schema;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.IntPredicate;
import java.util.function.IntToDoubleFunction;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// A nullable float column keeps its validity in the encoding that stores its values, as Rust's
/// arrays do (#604): ALP passes it to its encoded integers and patches only valid exceptions,
/// ALP-RD keeps it on its bit-packed left parts, a mostly-null column is sparse with a null fill,
/// and a column whose valid rows hold one value is a constant masked by a plain bitmap.
///
/// Each column goes through vortex-jni and vortex-java, and the two files must hold the same
/// encoding tree, data and zone stats table alike; vortex-jni then reads Java's file back.
class NullableFloatInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final ColumnName V = ColumnName.of("v");
    private static final int ROWS = 4 * 8192;
    private static final long[] RANDOM = new Random(7).longs(ROWS).toArray();

    static {
        NativeLoader.loadJni();
    }

    static Stream<Arguments> shapes() {
        IntPredicate every7 = i -> i % 7 == 0;
        IntToDoubleFunction twoDecimals = i -> Math.floorMod(RANDOM[i], 100_000) / 100.0;
        return Stream.of(
                // no null: written without validity, so the plain cascade (ALP, F32 included)
                shape("f64 two decimals, no nulls", PType.F64, twoDecimals, _ -> false),
                shape("f32 halves, no nulls", PType.F32, i -> Math.floorMod(RANDOM[i], 1000) * 0.5, _ -> false),
                shape("f32 one decimal, no nulls", PType.F32, i -> Math.floorMod(RANDOM[i], 10_000) / 10.0, _ -> false),
                shape("f32 two decimals, no nulls", PType.F32, twoDecimals, _ -> false),
                // ALP: the encoded ints bit-packed with the bitmap, patches only at valid rows
                shape("f64 two decimals", PType.F64, twoDecimals, every7),
                shape("f32 two decimals", PType.F32, twoDecimals, every7),
                // ALP-RD: the bitmap on the left parts
                shape("f64 random", PType.F64, i -> new Random(RANDOM[i]).nextDouble() * 1000, every7),
                // mostly null: sparse, its patch values left raw with their all-valid bitmap
                shape("f64 95% null", PType.F64, twoDecimals, i -> Math.floorMod(RANDOM[i] >>> 20, 100) < 95),
                shape("f64 constant with nulls", PType.F64, _ -> 1.5, every7),
                // few distinct: a flat dict whose codes keep the bitmap
                shape("f64 five distinct", PType.F64, i -> Math.floorMod(RANDOM[i], 5) * 0.25, every7),
                // taxi Airport_fee: one dominant value, two rare ones, nulls: a dict layout
                shape("f64 airport fee", PType.F64, i -> i % 17 == 0 ? 1.75 : i % 101 == 0 ? -1.75 : 0.0,
                        i -> i % 10 == 3));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shapes")
    void javaWritesTheEncodingTreeJniWrites(String name, PType ptype, IntToDoubleFunction values,
                                            IntPredicate isNull, @TempDir Path tmp) throws Exception {
        // Given
        Path jniFile = tmp.resolve("jni.vortex");
        Path javaFile = tmp.resolve("java.vortex");
        writeWithJni(jniFile, ptype, values, isNull);
        List<String> expected = EncodingTrees.of(jniFile);

        // When
        writeWithJava(javaFile, ptype, values, isNull, WriteOptions.defaults());

        // Then
        assertThat(EncodingTrees.of(javaFile)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shapes")
    void jniReadsJavaFile(String name, PType ptype, IntToDoubleFunction values, IntPredicate isNull,
                          @TempDir Path tmp) throws Exception {
        assertJniReads(ptype, values, isNull, WriteOptions.defaults(), tmp);
    }

    /// Compact writes add Pco, which keeps only the valid values and the bitmap as its child.
    /// vortex-jni cannot write compact files, so here only its read of Java's file checks them.
    @ParameterizedTest(name = "{0}")
    @MethodSource("shapes")
    void jniReadsJavaCompactFile(String name, PType ptype, IntToDoubleFunction values, IntPredicate isNull,
                                 @TempDir Path tmp) throws Exception {
        assertJniReads(ptype, values, isNull, WriteOptions.defaults().withCompact(true), tmp);
    }

    private static void assertJniReads(PType ptype, IntToDoubleFunction values, IntPredicate isNull,
                                       WriteOptions options, Path tmp) throws Exception {
        // Given
        Path file = tmp.resolve("java.vortex");
        writeWithJava(file, ptype, values, isNull, options);
        List<Double> expected = new ArrayList<>();
        for (int i = 0; i < ROWS; i++) {
            expected.add(isNull.test(i) ? null : stored(ptype, values.applyAsDouble(i)));
        }
        double threshold = stored(ptype, values.applyAsDouble(ROWS / 2));

        // When
        List<Double> result = readWithJni(file, ScanOptions.builder());
        // a filtered and a row-range read prune and slice, which a full scan never does
        List<Double> filtered = readWithJni(file, ScanOptions.builder().filter(Expression.binary(
                Expression.BinaryOp.GTE, Expression.column("v"), literal(ptype, threshold))));
        List<Double> range = readWithJni(file, ScanOptions.builder().rowRangeBegin(1000).rowRangeEnd(20_000));

        // Then
        assertThat(result).isEqualTo(expected);
        assertThat(filtered).isEqualTo(expected.stream().filter(v -> v != null && v >= threshold).toList());
        assertThat(range).isEqualTo(expected.subList(1000, 20_000));
    }

    private static Arguments shape(String name, PType ptype, IntToDoubleFunction values, IntPredicate isNull) {
        return Arguments.of(name, ptype, values, isNull);
    }

    /// The value as the column stores it: an F32 column rounds it to a float.
    private static double stored(PType ptype, double value) {
        if (ptype == PType.F32) {
            return (float) value;
        }
        return value;
    }

    private static Expression literal(PType ptype, double value) {
        if (ptype == PType.F32) {
            return Expression.literal((float) value);
        }
        return Expression.literal(value);
    }

    private static void writeWithJni(Path path, PType ptype, IntToDoubleFunction values, IntPredicate isNull)
            throws Exception {
        FloatingPointPrecision precision = FloatingPointPrecision.DOUBLE;
        if (ptype == PType.F32) {
            precision = FloatingPointPrecision.SINGLE;
        }
        Schema schema = new Schema(List.of(Field.nullable("v", new ArrowType.FloatingPoint(precision))));
        try (VortexWriter writer = VortexWriter.builder(SESSION, path.toAbsolutePath().toUri().toString(), schema,
                ALLOCATOR).build();
             VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
            FieldVector vector = root.getVector("v");
            vector.setInitialCapacity(ROWS);
            vector.allocateNew();
            for (int i = 0; i < ROWS; i++) {
                if (isNull.test(i)) {
                    vector.setNull(i);
                } else if (ptype == PType.F32) {
                    ((Float4Vector) vector).setSafe(i, (float) values.applyAsDouble(i));
                } else {
                    ((Float8Vector) vector).setSafe(i, values.applyAsDouble(i));
                }
            }
            vector.setValueCount(ROWS);
            root.setRowCount(ROWS);
            try (ArrowArray array = ArrowArray.allocateNew(ALLOCATOR);
                 ArrowSchema arrowSchema = ArrowSchema.allocateNew(ALLOCATOR)) {
                Data.exportVectorSchemaRoot(ALLOCATOR, root, null, array, arrowSchema);
                writer.writeBatch(array.memoryAddress(), arrowSchema.memoryAddress());
            }
        }
    }

    private static void writeWithJava(Path path, PType ptype, IntToDoubleFunction values, IntPredicate isNull,
                                      WriteOptions options) throws IOException {
        boolean[] valid = new boolean[ROWS];
        float[] floats = new float[ROWS];
        double[] doubles = new double[ROWS];
        for (int i = 0; i < ROWS; i++) {
            valid[i] = !isNull.test(i);
            // a null slot holds 0, as an Arrow null does
            double v = valid[i] ? values.applyAsDouble(i) : 0;
            floats[i] = (float) v;
            doubles[i] = v;
        }
        Object data = ptype == PType.F32 ? floats : doubles;
        DType.Struct schema = new DType.Struct(List.of(V), List.of(new DType.Primitive(ptype, true)), false);
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = io.github.dfa1.vortex.writer.VortexWriter.create(channel, schema, options)) {
            writer.writeChunk(Map.of(V, new NullableData(data, valid)));
        }
    }

    private static List<Double> readWithJni(Path file, ImmutableScanOptions.Builder options) throws IOException {
        ScanOptions opts = options.projection(Expression.select(new String[]{"v"}, Expression.root())).build();
        List<Double> out = new ArrayList<>();
        DataSource ds = DataSource.open(SESSION, file.toAbsolutePath().toUri().toString());
        Scan scan = ds.scan(opts);
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    FieldVector vec = root.getVector("v");
                    for (int i = 0; i < root.getRowCount(); i++) {
                        out.add(vec.isNull(i) ? null : ((Number) vec.getObject(i)).doubleValue());
                    }
                }
            }
        }
        return out;
    }
}
