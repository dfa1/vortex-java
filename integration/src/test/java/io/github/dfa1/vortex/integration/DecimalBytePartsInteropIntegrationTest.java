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
import io.github.dfa1.vortex.writer.WriteOptions;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.FieldVector;
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
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.IntToLongFunction;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// A decimal column is written as vortex-jni writes it: `vortex.decimal_byte_parts` whose value child
/// is narrowed to the smallest integer type and compressed by the cascade, as Rust's `DecimalScheme`
/// does (`narrowed_decimal`, then `compress_child`). The Java side kept the child as a plain i64
/// `vortex.primitive`, 2.5–16x larger on TPC-H's decimal columns (#644).
///
/// Only the data column is compared: the zone-stats table's min/max columns are nullable, and a
/// nullable decimal is still wrapped in `vortex.masked` where Rust keeps or drops its validity on the
/// byte parts (#646).
class DecimalBytePartsInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final ColumnName D = ColumnName.of("d");
    private static final int ROWS = 4 * 8192;
    private static final long[] RANDOM = new Random(644).longs(ROWS).toArray();

    static {
        NativeLoader.loadJni();
    }

    static Stream<Arguments> shapes() {
        return Stream.of(
                // TPC-H l_quantity: whole units 1..50 at scale 2, i.e. 100..5000 — fits an i16
                Arguments.of("quantity", 15, 2, (IntToLongFunction) i -> (1 + Math.floorMod(RANDOM[i], 50)) * 100L),
                // l_discount: 0.00..0.10 — fits an i8
                Arguments.of("discount", 15, 2, (IntToLongFunction) i -> Math.floorMod(RANDOM[i], 11)),
                // l_extendedprice: up to ~105,000.00 — an i32
                Arguments.of("extended price", 15, 2, (IntToLongFunction) i -> 90_000L + Math.floorMod(RANDOM[i], 10_400_000L)),
                // c_acctbal: -999.99..9999.99, a large offset FoR helps with
                Arguments.of("account balance", 15, 2, (IntToLongFunction) i -> Math.floorMod(RANDOM[i], 1_100_000L) - 99_999L),
                // values only an i64 holds
                Arguments.of("wide", 18, 0, (IntToLongFunction) i -> 1_000_000_000_000L + Math.floorMod(RANDOM[i], 1_000_000L)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shapes")
    void javaWritesTheEncodingTreeJniWrites(String name, int precision, int scale, IntToLongFunction unscaled,
                                            @TempDir Path tmp) throws Exception {
        // Given
        Path jniFile = tmp.resolve("jni.vortex");
        Path javaFile = tmp.resolve("java.vortex");
        writeWithJni(jniFile, precision, scale, unscaled);
        List<String> expected = EncodingTrees.of(jniFile);

        // When
        writeWithJava(javaFile, precision, scale, unscaled);

        // Then
        assertThat(dataColumn(expected).getFirst()).contains("vortex.decimal_byte_parts");
        assertThat(dataColumn(EncodingTrees.of(javaFile))).isEqualTo(dataColumn(expected));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("shapes")
    void jniReadsJavaFile(String name, int precision, int scale, IntToLongFunction unscaled,
                          @TempDir Path tmp) throws Exception {
        // Given
        Path file = tmp.resolve("java.vortex");
        writeWithJava(file, precision, scale, unscaled);
        List<BigDecimal> expected = new ArrayList<>(ROWS);
        for (int i = 0; i < ROWS; i++) {
            expected.add(BigDecimal.valueOf(unscaled.applyAsLong(i), scale));
        }
        BigDecimal threshold = expected.get(ROWS / 2);

        // When
        List<BigDecimal> all = readWithJni(file, ScanOptions.builder());
        // a filtered and a row-range read prune and slice, which a full scan never does
        List<BigDecimal> filtered = readWithJni(file, ScanOptions.builder().filter(Expression.binary(
                Expression.BinaryOp.GTE, Expression.column("d"),
                Expression.literalDecimal(threshold.unscaledValue(), precision, scale))));
        List<BigDecimal> range = readWithJni(file, ScanOptions.builder().rowRangeBegin(1000).rowRangeEnd(20_000));

        // Then
        assertThat(all).isEqualTo(expected);
        assertThat(filtered).isEqualTo(expected.stream().filter(v -> v.compareTo(threshold) >= 0).toList());
        assertThat(range).isEqualTo(expected.subList(1000, 20_000));
    }

    /// The data column's encoding subtree: the lines under the zoned layout's first flat, up to the
    /// stats table's flat.
    private static List<String> dataColumn(List<String> tree) {
        int start = tree.indexOf("    vortex.flat") + 1;
        int end = tree.subList(start, tree.size()).indexOf("    vortex.flat");
        return tree.subList(start, end < 0 ? tree.size() : start + end);
    }

    private static void writeWithJni(Path path, int precision, int scale, IntToLongFunction unscaled)
            throws Exception {
        Schema schema = new Schema(List.of(Field.notNullable("d", new ArrowType.Decimal(precision, scale, 128))));
        try (VortexWriter writer = VortexWriter.builder(SESSION, path.toAbsolutePath().toUri().toString(), schema,
                ALLOCATOR).build();
             VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
            DecimalVector vector = (DecimalVector) root.getVector("d");
            vector.setInitialCapacity(ROWS);
            vector.allocateNew();
            for (int i = 0; i < ROWS; i++) {
                vector.setSafe(i, BigDecimal.valueOf(unscaled.applyAsLong(i), scale));
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

    private static void writeWithJava(Path path, int precision, int scale, IntToLongFunction unscaled)
            throws IOException {
        BigDecimal[] values = new BigDecimal[ROWS];
        for (int i = 0; i < ROWS; i++) {
            values[i] = new BigDecimal(BigInteger.valueOf(unscaled.applyAsLong(i)), scale);
        }
        DType.Struct schema = new DType.Struct(List.of(D), List.of(DType.Decimal.of(precision, scale, false)), false);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = io.github.dfa1.vortex.writer.VortexWriter.create(channel, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of(D, values));
        }
    }

    private static List<BigDecimal> readWithJni(Path file, ImmutableScanOptions.Builder options) throws IOException {
        ScanOptions opts = options.projection(Expression.select(new String[]{"d"}, Expression.root())).build();
        List<BigDecimal> out = new ArrayList<>();
        DataSource ds = DataSource.open(SESSION, file.toAbsolutePath().toUri().toString());
        Scan scan = ds.scan(opts);
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    FieldVector vec = root.getVector("d");
                    for (int i = 0; i < root.getRowCount(); i++) {
                        out.add((BigDecimal) vec.getObject(i));
                    }
                }
            }
        }
        return out;
    }
}
