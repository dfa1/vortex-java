package io.github.dfa1.vortex.integration;

import dev.vortex.api.Session;
import dev.vortex.api.VortexWriter;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.inspect.InspectorTree;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.writer.WriteOptions;
import io.github.dfa1.vortex.writer.encode.NullableData;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.Float8Vector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Rust's dict layout admits a column only when its probe compressor, the full cascade, turns the
/// column's first 8192-row block into a `vortex.dict` (`DictStrategy`); a cardinality rule of thumb
/// admits columns Rust leaves to bit-packing. Each column goes through vortex-jni and vortex-java,
/// and both must agree on whether it gets a dict layout.
class DictAdmissionInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final ColumnName V = ColumnName.of("v");
    private static final int ROWS = 20_000;

    static {
        NativeLoader.loadJni();
    }

    /// The column type. The wide kinds spread their values over the whole range, where a
    /// dictionary beats bit-packing; the others keep them small.
    private enum Kind { I32, I64, WIDE_I64, F64, WIDE_F64, UTF8 }

    static Stream<Arguments> columns() {
        return Stream.of(
                // wide values: a dictionary beats the values
                Arguments.of("wide i64, 100 distinct", Kind.WIDE_I64, 100, 0),
                Arguments.of("wide f64, 10 distinct", Kind.WIDE_F64, 10, 0),
                // few small values: bit-packing a few bits a row beats a dictionary
                Arguments.of("i64, 4 distinct", Kind.I64, 4, 0),
                Arguments.of("f64, 5 distinct", Kind.F64, 5, 0),
                Arguments.of("utf8, 5 distinct", Kind.UTF8, 5, 0),
                Arguments.of("nullable i32, 10 distinct", Kind.I32, 10, 7),
                // under half distinct, yet bit-packing 7 bits wins over a 100-value dictionary.
                // A nullable column with 1000 distinct values still gets a dict in Java only: its
                // admission keeps the cardinality check until the nullable cascade charges
                // bit-packing the validity, as Rust's sampled sizes do.
                Arguments.of("i64, 100 distinct", Kind.I64, 100, 0),
                Arguments.of("i32, 1000 distinct", Kind.I32, 1000, 0),
                Arguments.of("utf8, 1000 distinct", Kind.UTF8, 1000, 0),
                Arguments.of("utf8, mostly distinct", Kind.UTF8, 1_000_000, 0));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("columns")
    void javaGivesADictLayoutWhereJniDoes(String name, Kind kind, int distinct, int nullEvery, @TempDir Path tmp)
            throws Exception {
        // Given
        long[] draws = new Random(3).longs(ROWS, 0, distinct).toArray();
        Path jniFile = tmp.resolve("jni.vortex");
        Path javaFile = tmp.resolve("java.vortex");
        writeWithJni(jniFile, kind, draws, nullEvery);
        boolean expected = hasDictLayout(jniFile);

        // When
        writeWithJava(javaFile, kind, draws, nullEvery);

        // Then
        assertThat(hasDictLayout(javaFile)).as("vortex-jni dict layout: %s", expected).isEqualTo(expected);
    }

    private static boolean isNull(int row, int nullEvery) {
        return nullEvery > 0 && row % nullEvery == 0;
    }

    private static long i64(Kind kind, long draw) {
        return kind == Kind.WIDE_I64 ? draw * 0x9E3779B97F4A7C15L : draw;
    }

    private static double f64(Kind kind, long draw) {
        return kind == Kind.WIDE_F64 ? new Random(draw).nextDouble() * 1000 : draw * 0.25;
    }

    private static String string(long draw) {
        return "value-" + draw;
    }

    private static void writeWithJni(Path path, Kind kind, long[] draws, int nullEvery) throws Exception {
        ArrowType type = switch (kind) {
            case I32 -> new ArrowType.Int(32, true);
            case I64, WIDE_I64 -> new ArrowType.Int(64, true);
            case F64, WIDE_F64 -> new ArrowType.FloatingPoint(FloatingPointPrecision.DOUBLE);
            case UTF8 -> new ArrowType.Utf8();
        };
        Field field = nullEvery > 0 ? Field.nullable("v", type) : Field.notNullable("v", type);
        Schema schema = new Schema(List.of(field));
        try (VortexWriter writer = VortexWriter.builder(SESSION, path.toAbsolutePath().toUri().toString(), schema,
                ALLOCATOR).build();
             VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
            FieldVector vector = root.getVector("v");
            vector.setInitialCapacity(ROWS);
            vector.allocateNew();
            for (int i = 0; i < ROWS; i++) {
                if (isNull(i, nullEvery)) {
                    vector.setNull(i);
                    continue;
                }
                switch (kind) {
                    case I32 -> ((IntVector) vector).setSafe(i, (int) draws[i]);
                    case I64, WIDE_I64 -> ((BigIntVector) vector).setSafe(i, i64(kind, draws[i]));
                    case F64, WIDE_F64 -> ((Float8Vector) vector).setSafe(i, f64(kind, draws[i]));
                    case UTF8 -> ((VarCharVector) vector).setSafe(i, string(draws[i]).getBytes(StandardCharsets.UTF_8));
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

    private static void writeWithJava(Path path, Kind kind, long[] draws, int nullEvery) throws IOException {
        boolean nullable = nullEvery > 0;
        DType dtype = switch (kind) {
            case I32 -> new DType.Primitive(PType.I32, nullable);
            case I64, WIDE_I64 -> new DType.Primitive(PType.I64, nullable);
            case F64, WIDE_F64 -> new DType.Primitive(PType.F64, nullable);
            case UTF8 -> new DType.Utf8(nullable);
        };
        Object values = switch (kind) {
            case I32 -> Arrays.stream(draws).mapToInt(d -> (int) d).toArray();
            case I64, WIDE_I64 -> Arrays.stream(draws).map(d -> i64(kind, d)).toArray();
            case F64, WIDE_F64 -> Arrays.stream(draws).mapToDouble(d -> f64(kind, d)).toArray();
            case UTF8 -> Arrays.stream(draws).mapToObj(DictAdmissionInteropIntegrationTest::string)
                    .toArray(String[]::new);
        };
        Object data = values;
        if (nullable) {
            boolean[] valid = new boolean[ROWS];
            for (int i = 0; i < ROWS; i++) {
                valid[i] = !isNull(i, nullEvery);
            }
            data = new NullableData(values, valid);
        }
        DType.Struct schema = new DType.Struct(List.of(V), List.of(dtype), false);
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = io.github.dfa1.vortex.writer.VortexWriter.create(channel, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of(V, data));
        }
    }

    private static boolean hasDictLayout(Path file) throws IOException {
        try (VortexReader reader = VortexReader.open(file)) {
            return hasDictLayout(InspectorTree.build(reader).root());
        }
    }

    private static boolean hasDictLayout(InspectorTree.Node node) {
        if (node.layout().isDict()) {
            return true;
        }
        return node.children().stream().anyMatch(DictAdmissionInteropIntegrationTest::hasDictLayout);
    }
}
