package io.github.dfa1.vortex.integration;

import dev.vortex.api.Session;
import dev.vortex.api.VortexWriter;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.inspect.VortexInspector;
import io.github.dfa1.vortex.integration.FixedSizeListInteropIntegrationTest.Nulls;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.DecimalArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.NullArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import org.apache.arrow.c.ArrowArray;
import org.apache.arrow.c.ArrowSchema;
import org.apache.arrow.c.Data;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.BigIntVector;
import org.apache.arrow.vector.DecimalVector;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VarBinaryVector;
import org.apache.arrow.vector.VarCharVector;
import org.apache.arrow.vector.VectorSchemaRoot;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Random;
import java.util.function.LongFunction;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/// Two columns the nullable-columns sweep did not cover: decimals (Rust writes `vortex.decimal`
/// with a validity child for some storage widths, which the Java decoder ignored) and long,
/// incompressible strings and binary (Rust still chooses FSST or `vortex.varbin`, never
/// `vortex.varbinview`, so the view decoder cannot be reached through vortex-jni).
class RustWritesNullableViewsAndDecimalsInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final ColumnName VALUE = ColumnName.of("v");
    private static final int BATCHES = 3;
    private static final int BATCH_ROWS = 1_000;
    private static final int ROWS = BATCHES * BATCH_ROWS;

    static {
        NativeLoader.loadJni();
    }

    enum Kind {UTF8, BINARY}

    static Stream<Arguments> viewCases() {
        List<Arguments> cases = new ArrayList<>();
        for (Kind kind : Kind.values()) {
            for (Nulls nulls : Nulls.values()) {
                cases.add(Arguments.of(kind, nulls));
            }
        }
        return cases.stream();
    }

    /// Random bytes of 13 to 60 per row: over 12 bytes, so no view is inlined, and incompressible,
    /// so neither FSST nor a dictionary pays and Rust keeps the canonical view layout.
    @ParameterizedTest(name = "{0}, {1}")
    @MethodSource("viewCases")
    void jniWriter_javaReader_longStringsKeepEveryNull(Kind kind, Nulls nulls, @TempDir Path tmp)
            throws IOException {
        // Given
        Path file = tmp.resolve("jni_views.vtx");
        ArrowType type = kind == Kind.UTF8 ? ArrowType.Utf8.INSTANCE : ArrowType.Binary.INSTANCE;
        write(file, Field.nullable("v", type), nulls, (vector, i, row) -> {
            byte[] bytes = randomBytes(row, kind == Kind.UTF8);
            if (vector instanceof VarCharVector chars) {
                chars.setSafe(i, bytes);
            } else {
                ((VarBinaryVector) vector).setSafe(i, bytes);
            }
        });

        // When
        List<Object> result = read(file, (inner, i) -> {
            VarBinArray strings = (VarBinArray) inner;
            return Base64.getEncoder().encodeToString(strings.getBytes(i));
        });

        // Then
        List<Object> expected = new ArrayList<>();
        for (long row = 0; row < ROWS; row++) {
            expected.add(nulls.isNull(row) ? null
                    : Base64.getEncoder().encodeToString(randomBytes(row, kind == Kind.UTF8)));
        }
        assertThat(result).as(() -> "encodings Rust chose: " + encodings(file))
                .containsExactlyElementsOf(expected);
    }

    static Stream<Arguments> decimalCases() {
        List<Arguments> cases = new ArrayList<>();
        // precision picks Rust's storage width: <=2 i8, <=4 i16, <=9 i32, <=18 i64, <=38 i128
        for (int[] width : new int[][]{{2, 0}, {4, 2}, {9, 2}, {18, 4}, {38, 10}}) {
            for (Nulls nulls : Nulls.values()) {
                for (String shape : new String[]{"distinct", "low cardinality", "constant"}) {
                    cases.add(Arguments.of(width[0], width[1], nulls, shape));
                }
            }
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "decimal({0},{1}), {2}, {3}")
    @MethodSource("decimalCases")
    void jniWriter_javaReader_decimalsKeepEveryNull(int precision, int scale, Nulls nulls, String shape,
                                                   @TempDir Path tmp) throws IOException {
        // Given
        LongFunction<BigDecimal> value = row -> decimal(precision, scale, switch (shape) {
            case "distinct" -> row;
            case "low cardinality" -> row % 5;
            default -> 7;
        });
        Path file = tmp.resolve("jni_decimals.vtx");
        write(file, Field.nullable("v", new ArrowType.Decimal(precision, scale, 128)), nulls,
                (vector, i, row) -> ((DecimalVector) vector).setSafe(i, value.apply(row)));

        // When
        List<Object> result = read(file, (inner, i) -> ((DecimalArray) inner).getDecimal(i));

        // Then
        List<Object> expected = new ArrayList<>();
        for (long row = 0; row < ROWS; row++) {
            expected.add(nulls.isNull(row) ? null : value.apply(row));
        }
        assertThat(result).as(() -> "encodings Rust chose: " + encodings(file))
                .containsExactlyElementsOf(expected);
    }

    /// Both ends of the range, so a width that is one byte too narrow shows.
    private static BigDecimal decimal(int precision, int scale, long key) {
        BigInteger max = BigInteger.TEN.pow(precision).subtract(BigInteger.ONE);
        BigInteger unscaled = switch ((int) (key % 4)) {
            case 0 -> max;
            case 1 -> max.negate();
            case 2 -> BigInteger.valueOf(key).mod(max.add(BigInteger.ONE));
            default -> new BigInteger(max.bitLength(), new Random(key)).mod(max.add(BigInteger.ONE)).negate();
        };
        return new BigDecimal(unscaled, scale);
    }

    private static byte[] randomBytes(long row, boolean printable) {
        Random random = new Random(row * 7919L + 1);
        byte[] bytes = new byte[13 + random.nextInt(48)];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = printable ? (byte) (33 + random.nextInt(94)) : (byte) random.nextInt(256);
        }
        return printable ? new String(bytes, StandardCharsets.US_ASCII).getBytes(StandardCharsets.UTF_8) : bytes;
    }

    private interface Setter {
        void set(FieldVector vector, int index, long row);
    }

    private static void write(Path file, Field value, Nulls nulls, Setter setter) throws IOException {
        Schema schema = new Schema(List.of(Field.notNullable("id", new ArrowType.Int(64, true)), value));
        try (VortexWriter writer = VortexWriter.builder(SESSION, file.toAbsolutePath().toUri().toString(), schema,
                ALLOCATOR).build()) {
            for (int batch = 0; batch < BATCHES; batch++) {
                try (VectorSchemaRoot root = VectorSchemaRoot.create(schema, ALLOCATOR)) {
                    BigIntVector ids = (BigIntVector) root.getVector("id");
                    FieldVector values = root.getVector("v");
                    ids.allocateNew(BATCH_ROWS);
                    values.allocateNew();
                    for (int i = 0; i < BATCH_ROWS; i++) {
                        long row = (long) batch * BATCH_ROWS + i;
                        ids.setSafe(i, row);
                        if (nulls.isNull(row)) {
                            setNull(values, i);
                        } else {
                            setter.set(values, i, row);
                        }
                    }
                    root.setRowCount(BATCH_ROWS);
                    try (ArrowArray array = ArrowArray.allocateNew(ALLOCATOR);
                         ArrowSchema exported = ArrowSchema.allocateNew(ALLOCATOR)) {
                        Data.exportVectorSchemaRoot(ALLOCATOR, root, null, array, exported);
                        writer.writeBatch(array.memoryAddress(), exported.memoryAddress());
                    }
                }
            }
        }
    }

    private static void setNull(FieldVector vector, int index) {
        switch (vector) {
            case VarCharVector v -> v.setNull(index);
            case VarBinaryVector v -> v.setNull(index);
            case DecimalVector v -> v.setNull(index);
            default -> throw new IllegalStateException("unexpected vector " + vector.getClass());
        }
    }

    private static List<Object> read(Path file, java.util.function.BiFunction<Array, Long, Object> reader)
            throws IOException {
        List<Object> rows = new ArrayList<>();
        try (VortexReader vortex = VortexReader.open(file, ReadRegistry.loadAll())) {
            vortex.scan(io.github.dfa1.vortex.reader.ScanOptions.all()).forEachRemaining(chunk -> {
                MaskedArray.Unwrapped column = MaskedArray.unwrap(chunk.column(VALUE));
                Array inner = column.inner();
                boolean allNull = inner instanceof NullArray;
                for (long i = 0; i < inner.length(); i++) {
                    boolean isNull = allNull || column.validity() != null && !column.validity().getBoolean(i);
                    rows.add(isNull ? null : reader.apply(inner, i));
                }
            });
        }
        return rows;
    }

    private static String encodings(Path file) {
        try (VortexReader reader = VortexReader.open(file, ReadRegistry.loadAll())) {
            return VortexInspector.inspect(reader).lines()
                    .filter(line -> line.startsWith("Used encodings")).findFirst().orElse("?");
        } catch (IOException e) {
            return "unreadable: " + e.getMessage();
        }
    }
}
