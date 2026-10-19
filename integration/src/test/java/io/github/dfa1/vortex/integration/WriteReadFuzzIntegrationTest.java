package io.github.dfa1.vortex.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import dev.vortex.api.DataSource;
import dev.vortex.api.Partition;
import dev.vortex.api.Scan;
import dev.vortex.api.ScanOptions;
import dev.vortex.api.Session;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.compute.PrimitiveArrays;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.DecimalArray;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.FloatArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.NullArray;
import io.github.dfa1.vortex.reader.array.ShortArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import io.github.dfa1.vortex.writer.encode.NullableData;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;

/// Write-side correctness fuzzing (#493): the fuzzer builds a small table, vortex-java writes it,
/// and a reader must give back exactly the values written.
///
/// The fuzzer does not choose every value: per column it picks a dtype, nullability and a *shape*
/// (random, constant, small range, runs, sorted, sparse) plus a seed, and the values follow. The
/// cascading compressor chooses its schemes from data statistics, so shapes are what steer it into
/// its edges (dict, RLE, FOR, delta, ALP, FSST, sparse patches, all-null, one-row chunks) while an
/// input stays a few bytes.
///
/// - [#javaRoundTrip(FuzzedDataProvider)]: vortex-java reads it back.
/// - [#jniReadsJavaWrite(FuzzedDataProvider)]: vortex-jni reads it back, so a file vortex-java
///   writes that Rust reads differently, or refuses, is a finding. A Rust panic aborts the JVM,
///   which ends the fuzzing run but still names the input on stderr.
///
/// Values compare in one canonical form: integers as `long` (unsigned ones zero-extended), floats
/// as their bit patterns (so `-0.0` and NaN are exact), booleans, strings and `null`.
@Tag("fuzz")
class WriteReadFuzzIntegrationTest {

    private static final HexFormat HEX = HexFormat.of();

    static {
        NativeLoader.loadJni();
    }

    private static final List<DType> TYPES = List.of(
            DType.I8, DType.I16, DType.I32, DType.I64, DType.U8, DType.U16, DType.U32, DType.U64,
            DType.F32, DType.F64, DType.BOOL, DType.UTF8, DType.BINARY,
            // One per decimal physical width Rust picks: i32, i64, i128
            DType.decimal(9, 2), DType.decimal(18, 4), DType.decimal(38, 0));

    @TempDir
    Path tempDir;

    private Path file;

    @BeforeEach
    void setUp() {
        file = tempDir.resolve("fuzz.vortex");
    }

    @FuzzTest
    void javaRoundTrip(FuzzedDataProvider data) throws IOException {
        // Given
        Table table = Table.generate(data);
        table.write(file);

        // When
        Map<ColumnName, List<Object>> result = read(() -> readJava(file, table), table);

        // Then
        assertEquals(table.expected(), result, "vortex-java reading " + table);
    }

    @FuzzTest
    void jniReadsJavaWrite(FuzzedDataProvider data) throws IOException {
        // Given
        Table table = Table.generate(data);
        table.write(file);

        // When
        Map<ColumnName, List<Object>> result = read(() -> readJni(file, table), table);

        // Then
        assertEquals(table.expected(), result, "vortex-jni reading " + table);
    }

    @FunctionalInterface
    private interface Read {
        Map<ColumnName, List<Object>> values() throws IOException;
    }

    /// Names the table in any read failure, so a finding says what was written.
    private static Map<ColumnName, List<Object>> read(Read read, Table table) {
        try {
            return read.values();
        } catch (IOException | RuntimeException e) {
            throw new AssertionError("reading " + table, e);
        }
    }

    /// One generated column: its schema entry, the array the writer takes, and the canonical values.
    private record Column(ColumnName name, DType dtype, String shape, Object values, List<Object> expected) {
    }

    private record Table(List<Column> columns, int rows, List<Integer> chunkRows) {

        static Table generate(FuzzedDataProvider data) {
            int rows = data.consumeInt(0, 3_000);
            List<Integer> chunkRows = new ArrayList<>();
            int left = rows;
            for (int chunks = data.consumeInt(1, 3); chunks > 1 && left > 0; chunks--) {
                int size = data.consumeInt(1, left);
                chunkRows.add(size);
                left -= size;
            }
            chunkRows.add(left);
            List<Column> columns = new ArrayList<>();
            for (int c = data.consumeInt(1, 4); c > 0; c--) {
                columns.add(column(data, ColumnName.of("c" + columns.size()), rows));
            }
            return new Table(columns, rows, chunkRows);
        }

        private static Column column(FuzzedDataProvider data, ColumnName name, int rows) {
            DType base = data.pickValue(TYPES);
            boolean nullable = data.consumeBoolean();
            Shape shape = data.pickValue(Shape.values());
            int nullEvery = nullable ? data.consumeInt(0, 4) : 0; // 0 = no nulls, 1 = all null
            Random random = new Random(data.consumeLong());
            long parameter = data.consumeLong();
            List<Object> expected = new ArrayList<>(rows);
            for (int i = 0; i < rows; i++) {
                boolean isNull = nullEvery == 1 || nullEvery > 1 && random.nextInt(nullEvery) == 0;
                expected.add(isNull ? null : canonical(base, shape.raw(random, i, parameter)));
            }
            DType dtype = nullable ? base.asNullable() : base;
            return new Column(name, dtype, shape.name(), writerValues(dtype, expected), expected);
        }

        /// The canonical value the raw shape draw stands for in `dtype`.
        private static Object canonical(DType dtype, long raw) {
            return switch (dtype) {
                case DType.Bool _ -> (raw & 1) != 0;
                case DType.Utf8 _ -> raw % 97 == 0 ? "" : "row-" + Long.toUnsignedString(raw, 36);
                // Arbitrary bytes, not UTF-8, of length 0..7: the raw's low bytes
                case DType.Binary _ -> HEX.formatHex(ByteBuffer.allocate(8).putLong(raw).array(), 0, (int) (raw & 7));
                // The raw's low digits, so the shapes (runs, small ranges, constants) carry over
                case DType.Decimal d -> BigDecimal.valueOf(raw % (long) Math.pow(10, Math.min(d.precision(), 18)), d.scale());
                case DType.Primitive p -> switch (p.ptype()) {
                    case I8 -> (long) (byte) raw;
                    case I16 -> (long) (short) raw;
                    case I32 -> (long) (int) raw;
                    case U8 -> raw & 0xFFL;
                    case U16 -> raw & 0xFFFFL;
                    case U32 -> raw & 0xFFFF_FFFFL;
                    case F32 -> Float.floatToIntBits(floatOf(raw));
                    case F64 -> Double.doubleToLongBits(doubleOf(raw));
                    default -> raw;
                };
                default -> throw new IllegalStateException(dtype.toString());
            };
        }

        /// Small raws become decimals (ALP's case), big ones arbitrary bit patterns (NaN, inf, -0.0).
        private static double doubleOf(long raw) {
            return Math.abs(raw) < 1_000_000 ? raw / 100.0 : Double.longBitsToDouble(raw);
        }

        private static float floatOf(long raw) {
            return Math.abs(raw) < 1_000_000 ? raw / 100.0f : Float.intBitsToFloat((int) raw);
        }

        /// The array [VortexWriter#writeChunk(Map)] takes: the column type's primitive array (or
        /// `String[]`), wrapped with its validity in [NullableData] when the column is nullable.
        private static Object writerValues(DType dtype, List<Object> canonical) {
            int n = canonical.size();
            if (dtype instanceof DType.Utf8) {
                return canonical.toArray(new String[0]);
            }
            if (dtype instanceof DType.Decimal) {
                return canonical.toArray(new BigDecimal[0]);
            }
            if (dtype instanceof DType.Binary) {
                return canonical.stream().map(v -> v == null ? null : HEX.parseHex((String) v)).toArray(byte[][]::new);
            }
            boolean[] valid = new boolean[n];
            long[] bits = new long[n];
            boolean[] bools = new boolean[n];
            for (int i = 0; i < n; i++) {
                Object v = canonical.get(i);
                valid[i] = v != null;
                if (v instanceof Boolean b) {
                    bools[i] = b;
                } else if (v != null) {
                    bits[i] = ((Number) v).longValue();
                }
            }
            Object values = dtype instanceof DType.Primitive p
                    ? PrimitiveArrays.fromBitsArray(bits, p.ptype(), EncodingId.VORTEX_PRIMITIVE)
                    : bools;
            return dtype.nullable() ? new NullableData(values, valid) : values;
        }

        void write(Path path) throws IOException {
            DType.Struct schema = new DType.Struct(columns.stream().map(Column::name).toList(),
                    columns.stream().map(Column::dtype).toList(), false);
            try (var channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING);
                 var writer = VortexWriter.create(channel, schema, WriteOptions.defaults())) {
                int from = 0;
                for (int size : chunkRows) {
                    Map<ColumnName, Object> chunk = new LinkedHashMap<>();
                    for (Column column : columns) {
                        chunk.put(column.name(), writerValues(column.dtype(), column.expected().subList(from, from + size)));
                    }
                    writer.writeChunk(chunk);
                    from += size;
                }
            }
        }

        Map<ColumnName, List<Object>> expected() {
            Map<ColumnName, List<Object>> expected = new LinkedHashMap<>();
            columns.forEach(c -> expected.put(c.name(), c.expected()));
            return expected;
        }

        @Override
        public String toString() {
            return rows + " rows in chunks " + chunkRows + ", columns " + columns.stream()
                    .map(c -> c.name() + ":" + c.dtype() + "/" + c.shape()).toList();
        }
    }

    /// How a column's raw values follow from its seed and parameter.
    private enum Shape {
        RANDOM {
            @Override
            long raw(Random random, int row, long parameter) {
                return random.nextLong();
            }
        },
        CONSTANT {
            @Override
            long raw(Random random, int row, long parameter) {
                return parameter;
            }
        },
        SMALL_RANGE {
            @Override
            long raw(Random random, int row, long parameter) {
                return parameter + random.nextInt(1 + (int) Math.floorMod(parameter, 64L));
            }
        },
        RUNS {
            @Override
            long raw(Random random, int row, long parameter) {
                return row / (1 + Math.floorMod(parameter, 500L));
            }
        },
        SORTED {
            @Override
            long raw(Random random, int row, long parameter) {
                return parameter + (long) row * (1 + Math.floorMod(parameter, 7L));
            }
        },
        SPARSE {
            @Override
            long raw(Random random, int row, long parameter) {
                return random.nextInt(100) == 0 ? random.nextLong() : parameter;
            }
        };

        abstract long raw(Random random, int row, long parameter);
    }

    private static Map<ColumnName, List<Object>> readJava(Path path, Table table) throws IOException {
        Map<ColumnName, List<Object>> read = new LinkedHashMap<>();
        table.columns().forEach(c -> read.put(c.name(), new ArrayList<>()));
        try (VortexReader reader = VortexReader.open(path)) {
            reader.scan(io.github.dfa1.vortex.reader.ScanOptions.all()).forEachRemaining(chunk -> {
                for (Column column : table.columns()) {
                    appendJava(chunk, column, read.get(column.name()));
                }
            });
        }
        return read;
    }

    private static void appendJava(Chunk chunk, Column column, List<Object> out) {
        MaskedArray.Unwrapped unwrapped = MaskedArray.unwrap(chunk.column(column.name()));
        Array values = unwrapped.inner();
        BoolArray validity = unwrapped.validity();
        for (long i = 0; i < values.length(); i++) {
            boolean isNull = values instanceof NullArray || validity != null && !validity.getBoolean(i);
            out.add(isNull ? null : javaValue(values, i, column.dtype()));
        }
    }

    private static Object javaValue(Array values, long i, DType dtype) {
        Object raw = switch (values) {
            case DecimalArray a -> a.getDecimal(i);
            case VarBinArray a when dtype instanceof DType.Binary -> HEX.formatHex(a.getBytes(i));
            case LongArray a -> a.getLong(i);
            case IntArray a -> (long) a.getInt(i);
            case ShortArray a -> (long) a.getShort(i);
            case ByteArray a -> (long) a.getByte(i);
            case DoubleArray a -> Double.doubleToLongBits(a.getDouble(i));
            case FloatArray a -> Float.floatToIntBits(a.getFloat(i));
            case BoolArray a -> a.getBoolean(i);
            case VarBinArray a -> a.getString(i);
            default -> throw new IllegalStateException("unexpected " + values.getClass().getSimpleName() + " for " + dtype);
        };
        return raw instanceof Long l ? zeroExtend(dtype, l) : raw;
    }

    private static Map<ColumnName, List<Object>> readJni(Path path, Table table) throws IOException {
        Map<ColumnName, List<Object>> read = new LinkedHashMap<>();
        table.columns().forEach(c -> read.put(c.name(), new ArrayList<>()));
        Scan scan = DataSource.open(Session.create(), path.toAbsolutePath().toUri().toString())
                .scan(ScanOptions.builder().build());
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ArrowAllocation.rootAllocator())) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    for (Column column : table.columns()) {
                        FieldVector vector = root.getVector(column.name().value());
                        for (int i = 0; i < root.getRowCount(); i++) {
                            read.get(column.name()).add(vector.isNull(i) ? null : jniValue(vector.getObject(i), column.dtype()));
                        }
                    }
                }
            }
        }
        return read;
    }

    private static Object jniValue(Object value, DType dtype) {
        return switch (value) {
            case Double d -> Double.doubleToLongBits(d);
            case Float f -> Float.floatToIntBits(f);
            case Boolean b -> b;
            case Character c -> (long) c;
            case BigDecimal d -> d;
            case byte[] b -> HEX.formatHex(b);
            case Number n -> zeroExtend(dtype, n.longValue());
            case org.apache.arrow.vector.util.Text text -> text.toString();
            default -> value.toString();
        };
    }

    /// Java's signed readings of an unsigned column back to its canonical, zero-extended form.
    private static long zeroExtend(DType dtype, long value) {
        if (!(dtype instanceof DType.Primitive p)) {
            return value;
        }
        return switch (p.ptype()) {
            case U8 -> value & 0xFFL;
            case U16 -> value & 0xFFFFL;
            case U32 -> value & 0xFFFF_FFFFL;
            default -> value;
        };
    }
}
