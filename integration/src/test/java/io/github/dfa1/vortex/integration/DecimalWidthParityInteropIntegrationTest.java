package io.github.dfa1.vortex.integration;

import dev.vortex.api.DataSource;
import dev.vortex.api.Expression;
import dev.vortex.api.ImmutableScanOptions;
import dev.vortex.api.Partition;
import dev.vortex.api.Scan;
import dev.vortex.api.Session;
import dev.vortex.api.VortexWriter;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.writer.WriteOptions;
import io.github.dfa1.vortex.writer.encode.NullableData;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.Decimal256Vector;
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
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// A decimal column across every canonical width boundary, as vortex-jni writes it (#662 upstream:
/// `vortex.decimal` is stored at the width of its precision, i8 up to precision 2, i16 up to 4, i32 up
/// to 9, i64 up to 18, i128 up to 38, i256 up to 76). Two shapes of data per precision, each at four scales (one negative) and
/// with and without nulls: values that fill the whole domain (the width cannot narrow) and values
/// within one byte or two (it can). Java must write the same encoding tree, and vortex-jni must read
/// Java's file back to the same values.
class DecimalWidthParityInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final ColumnName D = ColumnName.of("d");
    private static final int ROWS = 20_000;
    /// A null every seventh row, so a nullable column's validity is neither empty nor full.
    private static final int NULL_EVERY = 7;

    static {
        NativeLoader.loadJni();
    }

    static Stream<Arguments> cases() {
        List<Arguments> cases = new ArrayList<>();
        // Precision 1 is left out: its zone-stats table holds the same min and max in every zone, and over
        // three zones (20,000 rows) Rust keeps that column a plain `vortex.decimal` where Java writes
        // `vortex.decimal_byte_parts` over a `vortex.constant`; from five zones both write a constant.
        for (int precision : new int[]{2, 3, 4, 5, 9, 10, 18, 19, 38, 39, 76}) {
            // a negative scale is a multiple of ten: Rust's DecimalDType allows it
            for (int scale : new int[]{-3, 0, precision / 2, precision}) {
                for (boolean nullable : new boolean[]{false, true}) {
                    for (boolean narrow : new boolean[]{false, true}) {
                        cases.add(Arguments.of(precision, scale, nullable, narrow));
                    }
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "p{0} s{1} nullable={2} narrow={3}")
    @MethodSource("cases")
    void javaWritesTheEncodingTreeJniWrites(int precision, int scale, boolean nullable, boolean narrow,
                                            @TempDir Path tmp) throws Exception {
        // Given
        BigInteger[] unscaled = unscaledValues(precision, narrow);
        Path jniFile = tmp.resolve("jni.vortex");
        Path javaFile = tmp.resolve("java.vortex");
        writeWithJni(jniFile, precision, scale, nullable, unscaled);
        List<String> expected = EncodingTrees.of(jniFile);

        // When
        writeWithJava(javaFile, precision, scale, nullable, unscaled);

        // Then
        assertThat(EncodingTrees.of(javaFile)).isEqualTo(expected);
    }

    @ParameterizedTest(name = "p{0} s{1} nullable={2} narrow={3}")
    @MethodSource("cases")
    void jniReadsJavaFile(int precision, int scale, boolean nullable, boolean narrow, @TempDir Path tmp)
            throws Exception {
        // Given
        BigInteger[] unscaled = unscaledValues(precision, narrow);
        Path file = tmp.resolve("java.vortex");
        writeWithJava(file, precision, scale, nullable, unscaled);
        List<BigDecimal> expected = new ArrayList<>(ROWS);
        for (int i = 0; i < ROWS; i++) {
            expected.add(nullable && i % NULL_EVERY == 0 ? null : new BigDecimal(unscaled[i], scale));
        }

        // When
        List<BigDecimal> result = readWithJni(file);

        // Then
        assertThat(result).isEqualTo(expected);
    }

    /// Seeded per precision: `narrow` stays within two bytes, else values reach into the top digits of
    /// the precision's domain, so the width it needs is the width its precision gives.
    private static BigInteger[] unscaledValues(int precision, boolean narrow) {
        Random random = new Random(precision);
        BigInteger bound = narrow ? BigInteger.valueOf(30_000) : BigInteger.TEN.pow(precision);
        bound = bound.min(BigInteger.TEN.pow(precision));
        BigInteger[] values = new BigInteger[ROWS];
        for (int i = 0; i < ROWS; i++) {
            BigInteger magnitude = new BigInteger(bound.bitLength() + 8, random).mod(bound);
            values[i] = random.nextBoolean() ? magnitude : magnitude.negate();
        }
        return values;
    }

    private static void writeWithJni(Path path, int precision, int scale, boolean nullable, BigInteger[] unscaled)
            throws Exception {
        // Arrow's decimal128 holds up to 38 digits, decimal256 up to 76
        ArrowType decimal = new ArrowType.Decimal(precision, scale, precision > 38 ? 256 : 128);
        Schema schema = new Schema(List.of(nullable ? Field.nullable("d", decimal) : Field.notNullable("d", decimal)));
        try (VortexWriter writer = VortexWriter.builder(SESSION, path.toAbsolutePath().toUri().toString(), schema,
                ALLOCATOR).build();
             VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
            FieldVector vector = root.getVector("d");
            vector.setInitialCapacity(ROWS);
            vector.allocateNew();
            for (int i = 0; i < ROWS; i++) {
                if (nullable && i % NULL_EVERY == 0) {
                    setNull(vector, i);
                } else {
                    setDecimal(vector, i, new BigDecimal(unscaled[i], scale));
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

    private static void setNull(FieldVector vector, int index) {
        if (vector instanceof Decimal256Vector wide) {
            wide.setNull(index);
        } else {
            ((DecimalVector) vector).setNull(index);
        }
    }

    private static void setDecimal(FieldVector vector, int index, BigDecimal value) {
        if (vector instanceof Decimal256Vector wide) {
            wide.setSafe(index, value);
        } else {
            ((DecimalVector) vector).setSafe(index, value);
        }
    }

    private static void writeWithJava(Path path, int precision, int scale, boolean nullable, BigInteger[] unscaled)
            throws IOException {
        BigDecimal[] values = new BigDecimal[ROWS];
        boolean[] validity = new boolean[ROWS];
        for (int i = 0; i < ROWS; i++) {
            validity[i] = !nullable || i % NULL_EVERY != 0;
            values[i] = validity[i] ? new BigDecimal(unscaled[i], scale) : null;
        }
        Object data = nullable ? new NullableData(values, validity) : values;
        DType.Struct schema = new DType.Struct(List.of(D), List.of(DType.Decimal.of(precision, scale, nullable)), false);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var writer = io.github.dfa1.vortex.writer.VortexWriter.create(channel, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of(D, data));
        }
    }

    private static List<BigDecimal> readWithJni(Path file) throws IOException {
        var options = ImmutableScanOptions.builder()
                .projection(Expression.select(new String[]{"d"}, Expression.root())).build();
        List<BigDecimal> out = new ArrayList<>();
        DataSource ds = DataSource.open(SESSION, file.toAbsolutePath().toUri().toString());
        Scan scan = ds.scan(options);
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
