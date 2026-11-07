package io.github.dfa1.vortex.integration;

import dev.hardwood.OutputFile;
import dev.hardwood.metadata.LogicalType;
import dev.hardwood.metadata.PhysicalType;
import dev.hardwood.metadata.RepetitionType;
import dev.hardwood.schema.FileSchema;
import dev.hardwood.writer.ParquetFileWriter;
import dev.vortex.api.DataSource;
import dev.vortex.api.Expression;
import dev.vortex.api.ImmutableScanOptions;
import dev.vortex.api.Partition;
import dev.vortex.api.Scan;
import dev.vortex.api.ScanOptions;
import dev.vortex.api.Session;
import dev.vortex.arrow.ArrowAllocation;
import dev.vortex.jni.NativeLoader;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.TimeUnit;
import io.github.dfa1.vortex.core.model.TimestampDtype;
import io.github.dfa1.vortex.parquet.ImportOptions;
import io.github.dfa1.vortex.parquet.ParquetExporter;
import io.github.dfa1.vortex.parquet.ParquetImporter;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import org.apache.arrow.memory.BufferAllocator;
import org.apache.arrow.vector.FieldVector;
import org.apache.arrow.vector.TimeStampVector;
import org.apache.arrow.vector.VectorSchemaRoot;
import org.apache.arrow.vector.ipc.ArrowReader;
import org.apache.arrow.vector.types.pojo.ArrowType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntPredicate;

import static org.assertj.core.api.Assertions.assertThat;

/// A Parquet file imported by [ParquetImporter] reads in vortex-jni with the values and nulls the
/// Parquet file holds. Each case writes its own Parquet input ([ParquetExporter] from a Java-written
/// Vortex file, or hardwood's writer directly), so it controls the exact Parquet column shape the
/// Raincloud corpus tripped on.
class ParquetImportInteropIntegrationTest {

    private static final Session SESSION = Session.create();
    private static final BufferAllocator ALLOCATOR = ArrowAllocation.rootAllocator();
    private static final ColumnName TS = ColumnName.of("ts");
    /// More than two zones (8192 rows), so the filtered read prunes and the range read slices.
    private static final int ROWS = 3 * 8192;
    /// 10^37: a 12-digit value times it reaches 49 digits, past an i128 (about 1.7e38).
    private static final BigInteger WIDE = BigInteger.TEN.pow(37);
    private static final long START_MICROS = 1_704_067_200_000_000L; // 2024-01-01T00:00:00Z

    static {
        NativeLoader.loadJni();
    }

    /// An OPTIONAL `TIMESTAMP` column with nulls, as in Raincloud's `bi-telco` (#627: the import
    /// failed as "declared REQUIRED"). Nulls only in the second half and small import chunks, so the
    /// writer's repartition coalesces a chunk without nulls followed by one with nulls.
    @Test
    void nullableTimestampWithNulls_jniReadsImportedFile(@TempDir Path tmp) throws Exception {
        // Given
        IntPredicate isNull = i -> i % 7 == 0 && i >= ROWS / 2;
        List<Long> expected = new ArrayList<>(ROWS);
        List<Instant> instants = new ArrayList<>(ROWS);
        for (int i = 0; i < ROWS; i++) {
            long micros = START_MICROS + i * 90_000_000L;
            expected.add(isNull.test(i) ? null : micros);
            instants.add(isNull.test(i) ? null : Instant.EPOCH.plusNanos(micros * 1_000L));
        }
        Path parquet = toParquet(tmp, TimestampDtype.of(TimeUnit.Microseconds, null, true), instants);
        Path imported = tmp.resolve("imported.vortex");
        long threshold = expected.get(ROWS / 2 + 1);

        // When
        ParquetImporter.importParquet(parquet, imported, ImportOptions.defaults().withChunkSize(4096));
        List<Long> all = readWithJni(imported, ScanOptions.builder());
        // a filtered and a row-range read prune and slice, which a full scan never does
        List<Long> filtered = readWithJni(imported, ScanOptions.builder().filter(Expression.binary(
                Expression.BinaryOp.GTE, Expression.column("ts"),
                Expression.literalTimestamp(threshold, Expression.TimeUnit.MICROSECONDS, null))));
        List<Long> range = readWithJni(imported, ScanOptions.builder().rowRangeBegin(1000).rowRangeEnd(20_000));

        // Then
        assertThat(all).isEqualTo(expected);
        assertThat(filtered).isEqualTo(expected.stream().filter(v -> v != null && v >= threshold).toList());
        assertThat(range).isEqualTo(expected.subList(1000, 20_000));
    }

    /// An OPTIONAL `DECIMAL` column in each Parquet physical encoding imports as a Vortex decimal
    /// that vortex-jni reads back as the same values with the column's precision and scale (#628:
    /// INT32/INT64 dropped the scale, FIXED_LEN_BYTE_ARRAY as in TPC-H lineitem and BYTE_ARRAY threw).
    @ParameterizedTest(name = "{0} DECIMAL({2},{3})")
    @CsvSource({
            // physical type, fixed length, precision, scale
            "INT32, 0, 9, 2",
            "INT64, 0, 18, 4",
            "FIXED_LEN_BYTE_ARRAY, 7, 15, 2",
            "BYTE_ARRAY, 0, 30, 3",
            "BYTE_ARRAY, 0, 50, 3", // past decimal128: values only an i256 holds
    })
    void decimalWithNulls_jniReadsImportedFile(PhysicalType type, int length, int precision, int scale,
                                               @TempDir Path tmp) throws Exception {
        // Given
        IntPredicate isNull = i -> i % 7 == 0 && i >= ROWS / 2;
        long bound = precision < 10 ? 99_999_999L : 999_999_999_999L;
        BigInteger[] unscaled = new BigInteger[ROWS];
        List<BigDecimal> expected = new ArrayList<>(ROWS);
        for (int i = 0; i < ROWS; i++) {
            // the product wraps 2 * bound many times, so values take both signs
            BigInteger value = BigInteger.valueOf(Math.floorMod(i * 7_919_919_919L, 2 * bound) - bound);
            if (precision > 38) {
                // scaled past what an i128 holds, so Rust reads real i256s
                value = value.multiply(WIDE);
            }
            if (isNull.test(i)) {
                value = BigInteger.ZERO;
            }
            unscaled[i] = value;
            expected.add(isNull.test(i) ? null : new BigDecimal(unscaled[i], scale));
        }
        Path parquet = decimalParquet(tmp, type, length, precision, scale, unscaled, isNull);
        Path imported = tmp.resolve("imported.vortex");
        BigInteger threshold = unscaled[ROWS / 2 + 1];

        // When
        ParquetImporter.importParquet(parquet, imported, ImportOptions.defaults().withChunkSize(4096));
        List<BigDecimal> all = readDecimalsWithJni(imported, ScanOptions.builder(), precision, scale);
        // a filtered and a row-range read prune and slice, which a full scan never does
        List<BigDecimal> filtered = readDecimalsWithJni(imported, ScanOptions.builder().filter(Expression.binary(
                Expression.BinaryOp.GTE, Expression.column("d"),
                Expression.literalDecimal(threshold, precision, scale))), precision, scale);
        List<BigDecimal> range = readDecimalsWithJni(imported, ScanOptions.builder().rowRangeBegin(1000).rowRangeEnd(20_000),
                precision, scale);

        // Then
        BigDecimal min = new BigDecimal(threshold, scale);
        assertThat(all).isEqualTo(expected);
        assertThat(filtered).isEqualTo(expected.stream().filter(v -> v != null && v.compareTo(min) >= 0).toList());
        assertThat(range).isEqualTo(expected.subList(1000, 20_000));
    }

    /// A nullable text column imported under a byte bound far below the row bound, so chunks close
    /// on bytes (#657): vortex-jni reads back every value, also through a filtered and a row-range read.
    @Test
    void textClosedOnByteBound_jniReadsImportedFile(@TempDir Path tmp) throws Exception {
        // Given
        String[] expected = new String[ROWS];
        byte[][] bytes = new byte[ROWS][];
        boolean[] nulls = new boolean[ROWS];
        for (int i = 0; i < ROWS; i++) {
            nulls[i] = i % 5 == 0;
            if (!nulls[i]) {
                expected[i] = "row-" + i + "-" + "x".repeat(i % 50);
                bytes[i] = expected[i].getBytes(StandardCharsets.UTF_8);
            } else {
                bytes[i] = new byte[0];
            }
        }
        Path parquet = tmp.resolve("text.parquet");
        FileSchema schema = FileSchema.builder("schema")
                .addColumn("s", PhysicalType.BYTE_ARRAY, RepetitionType.OPTIONAL, new LogicalType.StringType())
                .build();
        try (ParquetFileWriter writer = ParquetFileWriter.create(OutputFile.of(parquet), schema)) {
            writer.columnWriter().writeBatch(batch -> batch.bytes(0, bytes, nulls));
        }
        Path imported = tmp.resolve("imported.vortex");

        // When — about 100 rows a chunk
        ParquetImporter.importParquet(parquet, imported, ImportOptions.defaults().withMaxChunkBytes(4_000));
        List<String> all = readTextWithJni(imported, ScanOptions.builder());
        List<String> filtered = readTextWithJni(imported, ScanOptions.builder()
                .filter(Expression.isNotNull(Expression.column("s"))));
        List<String> range = readTextWithJni(imported, ScanOptions.builder().rowRangeBegin(1000).rowRangeEnd(20_000));

        // Then
        assertThat(all).containsExactly(expected);
        assertThat(filtered).containsExactlyElementsOf(Arrays.stream(expected).filter(Objects::nonNull).toList());
        assertThat(range).containsExactly(Arrays.copyOfRange(expected, 1000, 20_000));
    }

    private static Path toParquet(Path tmp, DType.Extension dtype, List<Instant> values) throws IOException {
        Path vortex = tmp.resolve("source.vortex");
        DType.Struct schema = new DType.Struct(List.of(TS), List.of(dtype), false);
        try (FileChannel channel = FileChannel.open(vortex, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(channel, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of(TS, values));
        }
        Path parquet = tmp.resolve("source.parquet");
        ParquetExporter.exportParquet(vortex, parquet);
        return parquet;
    }

    private static Path decimalParquet(Path tmp, PhysicalType type, int length, int precision, int scale,
                                       BigInteger[] unscaled, IntPredicate isNull) throws IOException {
        FileSchema.Builder builder = FileSchema.builder("schema");
        LogicalType decimal = new LogicalType.DecimalType(precision, scale);
        if (length > 0) {
            builder.addColumn("d", type, RepetitionType.OPTIONAL, length, decimal);
        } else {
            builder.addColumn("d", type, RepetitionType.OPTIONAL, decimal);
        }
        boolean[] nulls = new boolean[unscaled.length];
        for (int i = 0; i < nulls.length; i++) {
            nulls[i] = isNull.test(i);
        }
        Path parquet = tmp.resolve("decimal.parquet");
        try (ParquetFileWriter writer = ParquetFileWriter.create(OutputFile.of(parquet), builder.build())) {
            writer.columnWriter().writeBatch(batch -> {
                switch (type) {
                    case INT32 -> batch.ints(0, Arrays.stream(unscaled).mapToInt(BigInteger::intValueExact).toArray(), nulls);
                    case INT64 -> batch.longs(0, Arrays.stream(unscaled).mapToLong(BigInteger::longValueExact).toArray(), nulls);
                    case FIXED_LEN_BYTE_ARRAY -> batch.fixed(0, Arrays.stream(unscaled).map(v -> bigEndian(v, length))
                            .toArray(byte[][]::new), nulls);
                    case BYTE_ARRAY -> batch.bytes(0, Arrays.stream(unscaled).map(BigInteger::toByteArray)
                            .toArray(byte[][]::new), nulls);
                    default -> throw new IllegalArgumentException(type.toString());
                }
            });
        }
        return parquet;
    }

    /// `value` as `length` big-endian two's-complement bytes, sign-extended, as Parquet stores a
    /// fixed-length DECIMAL.
    private static byte[] bigEndian(BigInteger value, int length) {
        byte[] minimal = value.toByteArray();
        byte[] out = new byte[length];
        Arrays.fill(out, 0, length - minimal.length, (byte) (value.signum() < 0 ? 0xFF : 0));
        System.arraycopy(minimal, 0, out, length - minimal.length, minimal.length);
        return out;
    }

    /// Reads column `d`, checking every batch carries Arrow's decimal type at `precision`/`scale`.
    private static List<BigDecimal> readDecimalsWithJni(Path file, ImmutableScanOptions.Builder options,
                                                        int precision, int scale) throws IOException {
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
                    ArrowType.Decimal arrowType = (ArrowType.Decimal) vec.getField().getType();
                    assertThat(arrowType.getPrecision()).isEqualTo(precision);
                    assertThat(arrowType.getScale()).isEqualTo(scale);
                    for (int i = 0; i < root.getRowCount(); i++) {
                        out.add(vec.isNull(i) ? null : (BigDecimal) vec.getObject(i));
                    }
                }
            }
        }
        return out;
    }

    private static List<String> readTextWithJni(Path file, ImmutableScanOptions.Builder options) throws IOException {
        ScanOptions opts = options.projection(Expression.select(new String[]{"s"}, Expression.root())).build();
        List<String> out = new ArrayList<>();
        DataSource ds = DataSource.open(SESSION, file.toAbsolutePath().toUri().toString());
        Scan scan = ds.scan(opts);
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    FieldVector vec = root.getVector("s");
                    for (int i = 0; i < root.getRowCount(); i++) {
                        out.add(vec.isNull(i) ? null : vec.getObject(i).toString());
                    }
                }
            }
        }
        return out;
    }

    private static List<Long> readWithJni(Path file, ImmutableScanOptions.Builder options) throws IOException {
        ScanOptions opts = options.projection(Expression.select(new String[]{"ts"}, Expression.root())).build();
        List<Long> out = new ArrayList<>();
        DataSource ds = DataSource.open(SESSION, file.toAbsolutePath().toUri().toString());
        Scan scan = ds.scan(opts);
        while (scan.hasNext()) {
            Partition partition = scan.next();
            try (ArrowReader reader = partition.scanArrow(ALLOCATOR)) {
                while (reader.loadNextBatch()) {
                    VectorSchemaRoot root = reader.getVectorSchemaRoot();
                    TimeStampVector vec = (TimeStampVector) root.getVector("ts");
                    for (int i = 0; i < root.getRowCount(); i++) {
                        out.add(vec.isNull(i) ? null : vec.get(i));
                    }
                }
            }
        }
        return out;
    }
}
