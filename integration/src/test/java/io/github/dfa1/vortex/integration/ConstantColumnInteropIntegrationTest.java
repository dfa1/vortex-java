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
import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.writer.WriteOptions;
import io.github.dfa1.vortex.writer.encode.NullableData;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.IntVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Rust 0.95's `ConstantStrategy`: a column whose rows all hold one value, over every batch, is one
/// flat `vortex.constant` with no zone map, chunks or dictionary. Several batches, so the run is
/// held across them; a column that changes only in its last batch must go back to the normal
/// pipeline with every held row, as Rust replays its run.
class ConstantColumnInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final ColumnName V = ColumnName.of("v");
    private static final int BATCHES = 3;
    private static final int BATCH_ROWS = 20_000;
    private static final int ROWS = BATCHES * BATCH_ROWS;

    static {
        NativeLoader.loadJni();
    }

    static Stream<Arguments> columns() {
        return Stream.of(
                Arguments.of("constant i64", Field.notNullable("v", new ArrowType.Int(64, true)), DTypes.I64,
                        (IntFunction<Object>) row -> 42L),
                Arguments.of("all-null i32", Field.nullable("v", new ArrowType.Int(32, true)), DTypes.I32.asNullable(),
                        (IntFunction<Object>) row -> null),
                Arguments.of("constant string", Field.notNullable("v", ArrowType.Utf8.INSTANCE), DTypes.UTF8,
                        (IntFunction<Object>) row -> "same"),
                Arguments.of("constant until the last batch", Field.notNullable("v", new ArrowType.Int(64, true)),
                        DTypes.I64, (IntFunction<Object>) row -> row < 2 * BATCH_ROWS ? 42L : (long) row));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("columns")
    void javaWritesTheLayoutJniWrites(String name, Field field, DType dtype, IntFunction<Object> value,
            @TempDir Path tmp) throws Exception {
        // Given
        Path jni = tmp.resolve("jni.vortex");
        Path java = tmp.resolve("java.vortex");
        writeWithJni(jni, field, value);

        // When
        writeWithJava(java, dtype, value);

        // Then
        assertThat(EncodingTrees.withStats(java)).isEqualTo(EncodingTrees.withStats(jni));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("columns")
    void jniReadsJavaFile(String name, Field field, DType dtype, IntFunction<Object> value, @TempDir Path tmp)
            throws Exception {
        // Given
        Path file = tmp.resolve("java.vortex");
        writeWithJava(file, dtype, value);
        List<Object> expected = new ArrayList<>();
        for (int row = 0; row < ROWS; row++) {
            expected.add(value.apply(row));
        }

        // When
        List<Object> result = readWithJni(file, ScanOptions.builder());
        // a row-range read slices the one constant chunk, which a full scan never does
        List<Object> range = readWithJni(file, ScanOptions.builder().rowRangeBegin(1000).rowRangeEnd(45_000));

        // Then
        assertThat(result).isEqualTo(expected);
        assertThat(range).isEqualTo(expected.subList(1000, 45_000));
    }

    private static void writeWithJni(Path path, Field field, IntFunction<Object> value) throws Exception {
        Schema schema = new Schema(List.of(field));
        try (VortexWriter writer = VortexWriter.builder(SESSION, path.toAbsolutePath().toUri().toString(), schema,
                ALLOCATOR).build()) {
            for (int batch = 0; batch < BATCHES; batch++) {
                try (VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
                    FieldVector vector = root.getVector("v");
                    vector.allocateNew();
                    for (int i = 0; i < BATCH_ROWS; i++) {
                        Object v = value.apply(batch * BATCH_ROWS + i);
                        switch (vector) {
                            case FieldVector _ when v == null -> vector.setNull(i);
                            case BigIntVector longs -> longs.setSafe(i, (Long) v);
                            case IntVector ints -> ints.setSafe(i, (Integer) v);
                            case VarCharVector strings -> strings.setSafe(i, ((String) v).getBytes(StandardCharsets.UTF_8));
                            default -> throw new IllegalStateException("unexpected vector " + vector);
                        }
                    }
                    vector.setValueCount(BATCH_ROWS);
                    root.setRowCount(BATCH_ROWS);
                    try (ArrowArray array = ArrowArray.allocateNew(ALLOCATOR);
                         ArrowSchema arrowSchema = ArrowSchema.allocateNew(ALLOCATOR)) {
                        Data.exportVectorSchemaRoot(ALLOCATOR, root, null, array, arrowSchema);
                        writer.writeBatch(array.memoryAddress(), arrowSchema.memoryAddress());
                    }
                }
            }
        }
    }

    private static void writeWithJava(Path path, DType dtype, IntFunction<Object> value) throws IOException {
        DType.Struct schema = new DType.Struct(List.of(V), List.of(dtype), false);
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = io.github.dfa1.vortex.writer.VortexWriter.create(channel, schema, WriteOptions.defaults())) {
            for (int batch = 0; batch < BATCHES; batch++) {
                writer.writeChunk(Map.of(V, javaBatch(dtype, value, batch * BATCH_ROWS)));
            }
        }
    }

    /// One batch in the carrier Java's writer takes for `dtype`.
    private static Object javaBatch(DType dtype, IntFunction<Object> value, int first) {
        if (dtype.equals(DTypes.UTF8)) {
            String[] strings = new String[BATCH_ROWS];
            for (int i = 0; i < BATCH_ROWS; i++) {
                strings[i] = (String) value.apply(first + i);
            }
            return strings;
        }
        if (dtype.equals(DTypes.I64)) {
            long[] longs = new long[BATCH_ROWS];
            for (int i = 0; i < BATCH_ROWS; i++) {
                longs[i] = (Long) value.apply(first + i);
            }
            return longs;
        }
        // nullable i32: a null slot holds 0, as an Arrow null does
        int[] ints = new int[BATCH_ROWS];
        boolean[] valid = new boolean[BATCH_ROWS];
        for (int i = 0; i < BATCH_ROWS; i++) {
            Object v = value.apply(first + i);
            valid[i] = v != null;
            ints[i] = v == null ? 0 : (Integer) v;
        }
        return new NullableData(ints, valid);
    }

    private static List<Object> readWithJni(Path file, ImmutableScanOptions.Builder options) throws IOException {
        ScanOptions opts = options.projection(Expression.select(new String[]{"v"}, Expression.root())).build();
        List<Object> out = new ArrayList<>();
        DataSource ds = DataSource.open(SESSION, file.toAbsolutePath().toUri().toString());
        Scan scan = ds.scan(opts);
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    FieldVector vec = root.getVector("v");
                    for (int i = 0; i < root.getRowCount(); i++) {
                        Object v = vec.getObject(i);
                        // Arrow's Utf8 values come back as Text
                        out.add(v instanceof org.apache.arrow.vector.util.Text t ? t.toString() : v);
                    }
                }
            }
        }
        return out;
    }
}
