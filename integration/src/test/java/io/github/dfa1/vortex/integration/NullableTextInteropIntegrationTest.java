package io.github.dfa1.vortex.integration;

import dev.vortex.api.DataSource;
import dev.vortex.api.ImmutableScanOptions;
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
import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.writer.WriteOptions;
import io.github.dfa1.vortex.writer.encode.NullableData;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.FieldVector;
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
import java.util.Random;
import java.util.function.IntPredicate;
import java.util.function.IntFunction;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Each column goes through vortex-jni and vortex-java, and the two files must hold the same
/// encoding tree, data and zone stats table alike. Each shape steers the cascade to a different
/// winner.
/// A nullable text column without a null is written without a validity, as Rust writes an all-valid
/// string array (#650), instead of under `vortex.masked`. Columns with nulls are still masked, and
/// differ from Rust in the text scheme itself (#650), so the encoding tree is compared with
/// vortex-jni's for the no-null shape only; every shape is read back by Java and by vortex-jni,
/// full, filtered and row-ranged, compact files included.
class NullableTextInteropIntegrationTest {

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
        IntFunction<String> few = i -> "value-" + Math.floorMod(RANDOM[i], 10);
        IntFunction<String> many = i -> "some longer text value number " + Math.floorMod(RANDOM[i], 5000) + " end";
        IntFunction<String> unique = i -> "row-" + RANDOM[i];
        return Stream.of(
                shape("few distinct, no nulls", few, _ -> false),
                shape("many distinct, no nulls", many, _ -> false),
                shape("unique, no nulls", unique, _ -> false),
                shape("few distinct", few, every7),
                shape("many distinct", many, every7),
                shape("unique", unique, every7),
                shape("one null", few, i -> i == 5),
                shape("95% null", few, i -> Math.floorMod(RANDOM[i] >>> 20, 100) < 95),
                shape("runs of nulls", few, i -> (i / 500) % 3 == 0));
    }

    /// The shapes whose whole tree is Rust's. The others still differ beyond the validity, in the
    /// scheme chosen for the text itself (#650: the probe applies to numbers only; FSST's lengths).
    static Stream<Arguments> treeShapes() {
        return shapes().filter(a -> a.get()[0].equals("unique, no nulls"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("treeShapes")
    void javaWritesTheEncodingTreeJniWrites(String name, IntFunction<String> values, IntPredicate isNull,
                                            @TempDir Path tmp) throws Exception {
        // Given
        Path jniFile = tmp.resolve("jni.vortex");
        Path javaFile = tmp.resolve("java.vortex");
        writeWithJni(jniFile, values, isNull);
        List<String> expected = EncodingTrees.of(jniFile);

        // When
        writeWithJava(javaFile, values, isNull, WriteOptions.defaults());

        // Then
        assertThat(EncodingTrees.of(javaFile)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shapes")
    void jniReadsJavaFile(String name, IntFunction<String> values, IntPredicate isNull, @TempDir Path tmp)
            throws Exception {
        assertJniReads(values, isNull, WriteOptions.defaults(), tmp);
    }

    /// Compact writes add Zstd; vortex-jni cannot write them, so only its read of Java's file checks them.
    @ParameterizedTest(name = "{0}")
    @MethodSource("shapes")
    void jniReadsJavaCompactFile(String name, IntFunction<String> values, IntPredicate isNull, @TempDir Path tmp)
            throws Exception {
        assertJniReads(values, isNull, WriteOptions.defaults().withCompact(true), tmp);
    }

    /// Columns without a null: those are the plain values a Java read returns as strings.
    static Stream<Arguments> noNullShapes() {
        return shapes().filter(a -> a.get()[0].toString().endsWith("no nulls"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("noNullShapes")
    void javaReadsItsOwnFile(String name, IntFunction<String> values, IntPredicate isNull, @TempDir Path tmp)
            throws Exception {
        // Given
        Path file = tmp.resolve("java.vortex");
        writeWithJava(file, values, isNull, WriteOptions.defaults());

        // When
        List<String> result = readWithJava(file);

        // Then
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < ROWS; i++) {
            expected.add(values.apply(i));
        }
        assertThat(result).isEqualTo(expected);
    }

    private static void assertJniReads(IntFunction<String> values, IntPredicate isNull, WriteOptions options,
                                       Path tmp) throws Exception {
        // Given
        Path file = tmp.resolve("java.vortex");
        writeWithJava(file, values, isNull, options);
        List<String> expected = new ArrayList<>();
        for (int i = 0; i < ROWS; i++) {
            expected.add(isNull.test(i) ? null : values.apply(i));
        }
        String threshold = values.apply(ROWS / 2);

        // When
        List<String> result = readWithJni(file, ScanOptions.builder());
        // a filtered and a row-range read prune and slice, which a full scan never does
        List<String> filtered = readWithJni(file, ScanOptions.builder().filter(Expression.binary(
                Expression.BinaryOp.EQ, Expression.column("v"), Expression.literal(threshold))));
        List<String> range = readWithJni(file, ScanOptions.builder().rowRangeBegin(1000).rowRangeEnd(20_000));

        // Then
        assertThat(result).isEqualTo(expected);
        assertThat(filtered).isEqualTo(expected.stream().filter(threshold::equals).toList());
        assertThat(range).isEqualTo(expected.subList(1000, 20_000));
    }

    private static Arguments shape(String name, IntFunction<String> values, IntPredicate isNull) {
        return Arguments.of(name, values, isNull);
    }

    private static void writeWithJni(Path path, IntFunction<String> values, IntPredicate isNull) throws Exception {
        Schema schema = new Schema(List.of(Field.nullable("v", new ArrowType.Utf8())));
        try (VortexWriter writer = VortexWriter.builder(SESSION, path.toAbsolutePath().toUri().toString(), schema,
                ALLOCATOR).build();
             VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
            VarCharVector vector = (VarCharVector) root.getVector("v");
            vector.allocateNew();
            for (int i = 0; i < ROWS; i++) {
                if (isNull.test(i)) {
                    vector.setNull(i);
                } else {
                    vector.setSafe(i, values.apply(i).getBytes(StandardCharsets.UTF_8));
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

    private static void writeWithJava(Path path, IntFunction<String> values, IntPredicate isNull,
                                      WriteOptions options) throws IOException {
        boolean[] valid = new boolean[ROWS];
        String[] strings = new String[ROWS];
        for (int i = 0; i < ROWS; i++) {
            valid[i] = !isNull.test(i);
            strings[i] = valid[i] ? values.apply(i) : null;
        }
        DType.Struct schema = new DType.Struct(List.of(V), List.of(DTypes.UTF8.asNullable()), false);
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = io.github.dfa1.vortex.writer.VortexWriter.create(channel, schema, options)) {
            writer.writeChunk(Map.of(V, new NullableData(strings, valid)));
        }
    }

    private static List<String> readWithJava(Path file) throws IOException {
        List<String> out = new ArrayList<>();
        try (var reader = io.github.dfa1.vortex.reader.VortexReader.open(file);
             var scan = reader.scan(io.github.dfa1.vortex.reader.ScanOptions.all())) {
            while (scan.hasNext()) {
                try (var chunk = scan.next()) {
                    io.github.dfa1.vortex.reader.array.VarBinArray array = chunk.column(V);
                    for (int i = 0; i < chunk.rowCount(); i++) {
                        out.add(array.getString(i));
                    }
                }
            }
        }
        return out;
    }

    /// Partitions come back in vortex-jni's order, which is the file's for a single-chunk column (all
    /// the shapes here); a multi-chunk shape would need them ordered.
    private static List<String> readWithJni(Path file, ImmutableScanOptions.Builder options) throws IOException {
        ScanOptions opts = options.projection(Expression.select(new String[]{"v"}, Expression.root())).build();
        List<String> out = new ArrayList<>();
        DataSource ds = DataSource.open(SESSION, file.toAbsolutePath().toUri().toString());
        Scan scan = ds.scan(opts);
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    FieldVector vec = root.getVector("v");
                    for (int i = 0; i < root.getRowCount(); i++) {
                        out.add(vec.isNull(i) ? null : vec.getObject(i).toString());
                    }
                }
            }
        }
        return out;
    }
}
