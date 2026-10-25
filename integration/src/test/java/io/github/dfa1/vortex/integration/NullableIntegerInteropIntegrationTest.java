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
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.writer.WriteOptions;
import io.github.dfa1.vortex.writer.encode.NullableData;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.IntVector;
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
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.IntPredicate;
import java.util.function.IntToLongFunction;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// A nullable integer column keeps its validity in the encoding that stores its values, as Rust's
/// arrays do (#604): a `vortex.bool` child of the bit-packed or primitive leaf, passed down by
/// FoR, ZigZag, run-end, RLE and dict codes, never a `vortex.masked` wrapper. The cascade then
/// charges the validity to the schemes it samples, so it picks what Rust picks, and the dict
/// layout takes Rust's probe instead of a cardinality rule.
///
/// Each column goes through vortex-jni and vortex-java, and the two files must hold the same
/// encoding tree, data and zone stats table alike. Each shape steers the cascade to a different
/// winner. Not covered: a column whose bit-packing needs patches, as Rust also stores the patches'
/// chunk offsets and Java does not yet.
class NullableIntegerInteropIntegrationTest {

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
        return Stream.of(
                // no null: written without validity, as Rust writes an all-valid Arrow array
                shape("no nulls", PType.I32, i -> Math.floorMod(RANDOM[i], 16), _ -> false),
                // valid rows all 42: a constant masked by a plain bitmap
                shape("constant with nulls", PType.I32, _ -> 42, every7),
                // small values: bit-packed, the bitmap on the bit-packed array
                shape("small values", PType.I32, i -> Math.floorMod(RANDOM[i], 16), every7),
                // 10 distinct: a dict layout, its values pool keeping the null entry's bitmap
                shape("10 distinct", PType.I32, i -> Math.floorMod(RANDOM[i], 10), every7),
                // 1000 distinct: bit-packing 10 bits a row beats a dict once it pays for nulls
                shape("1000 distinct", PType.I32, i -> Math.floorMod(RANDOM[i], 1000), every7),
                // a large offset: frame of reference, then bit-packing with the bitmap
                shape("offset values", PType.I64, i -> 1_000_000_000L + Math.floorMod(RANDOM[i], 1000), every7),
                shape("negative values", PType.I64, i -> Math.floorMod(RANDOM[i], 1000) - 500, every7),
                shape("sorted steps", PType.I64, i -> i * 1000L + Math.floorMod(RANDOM[i], 7), every7),
                shape("sequence", PType.I32, i -> i, every7),
                // run of nulls: a run-end whose run values keep a bitmap
                shape("runs with null runs", PType.I32, i -> Math.floorMod(RANDOM[i / 6], 1000), i -> (i / 6) % 5 == 0),
                shape("runs with scattered nulls", PType.I32, i -> Math.floorMod(RANDOM[i / 6], 1000), i -> i % 13 == 0),
                shape("long runs", PType.I64, i -> (i / 100) * 37L, every7),
                // wide runs: RLE, its narrowed indices keeping the bitmap padded to a whole chunk
                shape("wide runs with null runs", PType.I64, i -> RANDOM[i / 20] >>> 20, i -> (i / 20) % 7 == 0),
                shape("wide runs with scattered nulls", PType.I64, i -> RANDOM[i / 20] >>> 20, i -> i % 13 == 0),
                // mostly null: sparse with a null fill, the valid rows as patches
                shape("95% null", PType.I32, i -> Math.floorMod(RANDOM[i], 1000), i -> Math.floorMod(RANDOM[i] >>> 20, 100) < 95),
                shape("clustered nulls", PType.I32, i -> Math.floorMod(RANDOM[i], 1000), i -> (i / 500) % 3 == 0),
                // one null in one zone: that zone's null count makes the stats table's dict
                shape("one null", PType.I32, i -> Math.floorMod(RANDOM[i], 1000), i -> i == 5));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shapes")
    void javaWritesTheEncodingTreeJniWrites(String name, PType ptype, IntToLongFunction values, IntPredicate isNull,
                                            @TempDir Path tmp) throws Exception {
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
    void jniReadsJavaFile(String name, PType ptype, IntToLongFunction values, IntPredicate isNull,
                          @TempDir Path tmp) throws Exception {
        assertJniReads(ptype, values, isNull, WriteOptions.defaults(), tmp);
    }

    /// Compact writes add Pco, which keeps only the valid values and the bitmap as its child.
    /// vortex-jni cannot write compact files, so here only its read of Java's file checks them.
    @ParameterizedTest(name = "{0}")
    @MethodSource("shapes")
    void jniReadsJavaCompactFile(String name, PType ptype, IntToLongFunction values, IntPredicate isNull,
                                 @TempDir Path tmp) throws Exception {
        assertJniReads(ptype, values, isNull, WriteOptions.defaults().withCompact(true), tmp);
    }

    private static void assertJniReads(PType ptype, IntToLongFunction values, IntPredicate isNull,
                                       WriteOptions options, Path tmp) throws Exception {
        // Given
        Path file = tmp.resolve("java.vortex");
        writeWithJava(file, ptype, values, isNull, options);
        List<Long> expected = new ArrayList<>();
        for (int i = 0; i < ROWS; i++) {
            expected.add(isNull.test(i) ? null : values.applyAsLong(i));
        }
        long threshold = values.applyAsLong(ROWS / 2);

        // When
        List<Long> result = readWithJni(file, ScanOptions.builder());
        // a filtered and a row-range read prune and slice, which a full scan never does
        List<Long> filtered = readWithJni(file, ScanOptions.builder().filter(Expression.binary(
                Expression.BinaryOp.GTE, Expression.column("v"), literal(ptype, threshold))));
        List<Long> range = readWithJni(file, ScanOptions.builder().rowRangeBegin(1000).rowRangeEnd(20_000));

        // Then
        assertThat(result).isEqualTo(expected);
        assertThat(filtered).isEqualTo(expected.stream().filter(v -> v != null && v >= threshold).toList());
        assertThat(range).isEqualTo(expected.subList(1000, 20_000));
    }

    private static Arguments shape(String name, PType ptype, IntToLongFunction values, IntPredicate isNull) {
        return Arguments.of(name, ptype, values, isNull);
    }

    private static Expression literal(PType ptype, long value) {
        if (ptype == PType.I32) {
            return Expression.literal((int) value);
        }
        return Expression.literal(value);
    }

    private static void writeWithJni(Path path, PType ptype, IntToLongFunction values, IntPredicate isNull)
            throws Exception {
        Schema schema = new Schema(List.of(Field.nullable("v", new ArrowType.Int(ptype.bits(), true))));
        try (VortexWriter writer = VortexWriter.builder(SESSION, path.toAbsolutePath().toUri().toString(), schema,
                ALLOCATOR).build();
             VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
            FieldVector vector = root.getVector("v");
            vector.setInitialCapacity(ROWS);
            vector.allocateNew();
            for (int i = 0; i < ROWS; i++) {
                if (isNull.test(i)) {
                    vector.setNull(i);
                } else if (ptype == PType.I32) {
                    ((IntVector) vector).setSafe(i, (int) values.applyAsLong(i));
                } else {
                    ((BigIntVector) vector).setSafe(i, values.applyAsLong(i));
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

    private static void writeWithJava(Path path, PType ptype, IntToLongFunction values, IntPredicate isNull,
                                      WriteOptions options) throws IOException {
        boolean[] valid = new boolean[ROWS];
        int[] ints = new int[ROWS];
        long[] longs = new long[ROWS];
        for (int i = 0; i < ROWS; i++) {
            valid[i] = !isNull.test(i);
            // a null slot holds 0, as an Arrow null does
            long v = valid[i] ? values.applyAsLong(i) : 0;
            ints[i] = (int) v;
            longs[i] = v;
        }
        Object data = ptype == PType.I32 ? ints : longs;
        DType.Struct schema = new DType.Struct(List.of(V), List.of(new DType.Primitive(ptype, true)), false);
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = io.github.dfa1.vortex.writer.VortexWriter.create(channel, schema, options)) {
            writer.writeChunk(Map.of(V, new NullableData(data, valid)));
        }
    }

    private static List<Long> readWithJni(Path file, ImmutableScanOptions.Builder options) throws IOException {
        ScanOptions opts = options.projection(Expression.select(new String[]{"v"}, Expression.root())).build();
        List<Long> out = new ArrayList<>();
        DataSource ds = DataSource.open(SESSION, file.toAbsolutePath().toUri().toString());
        Scan scan = ds.scan(opts);
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    FieldVector vec = root.getVector("v");
                    for (int i = 0; i < root.getRowCount(); i++) {
                        out.add(vec.isNull(i) ? null : ((Number) vec.getObject(i)).longValue());
                    }
                }
            }
        }
        return out;
    }
}
