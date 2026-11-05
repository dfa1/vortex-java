package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.EncodingId;
import io.github.dfa1.vortex.reader.ArrayStats;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ScanIterator;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.DecimalArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.writer.encode.NullableData;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// A nullable decimal keeps its validity in its own encoding rather than a `vortex.masked` wrapper
/// (#646): the bounds, the constant shape and the paths around the default cascade.
class NullableDecimalWriteTest {

    private static final ColumnName D = ColumnName.of("d");

    @TempDir
    Path tmp;

    @ParameterizedTest(name = "DECIMAL({0}, 2), cascade {1}")
    @CsvSource({"20, 3", "20, 0", "15, 3", "15, 0", "38, 3", "5, 0"})
    void nullableDecimal_boundsSkipTheNullRows(int precision, int cascade) throws Exception {
        // Given — the null row's slot holds a placeholder zero that is below every valid value
        BigDecimal[] values = {dec("0.05"), null, dec("0.07"), dec("0.09")};
        Path file = write(precision, WriteOptions.cascading(cascade), values);

        // When
        ArrayStats result = stats(file);

        // Then — the minimum is 0.05, not the placeholder
        assertThat(unscaled(result.min())).isEqualTo(5);
        assertThat(unscaled(result.max())).isEqualTo(9);
        assertThat(result.nullCount()).isEqualTo(1);
        assertThat(read(file)).containsExactly(values);
    }

    @ParameterizedTest(name = "DECIMAL({0}, 2)")
    @ValueSource(ints = {5, 15, 20, 38})
    void forcedColumnEncoding_onANullableDecimalWithNulls_roundTrips(int precision) throws Exception {
        // Given — a configured candidate list used to wrap the column in vortex.masked, whose constant
        // check then met the null row
        BigDecimal[] values = {dec("0.05"), null, dec("0.07")};
        WriteOptions options = WriteOptions.defaults().withColumnEncoding(D,
                ColumnEncoding.candidates(EncodingId.VORTEX_DECIMAL_BYTE_PARTS, EncodingId.VORTEX_PRIMITIVE));

        // When
        Path file = write(precision, options, values);

        // Then
        assertThat(read(file)).containsExactly(values);
    }

    @ParameterizedTest(name = "DECIMAL({0}, 2), {1}")
    @CsvSource({"3, 5", "10, -5", "15, 700", "20, -5", "38, 5", "5, 0"})
    void equalValidValues_withANull_areAMaskedConstant(int precision, String unscaled) throws Exception {
        // Given — Rust writes the valid values as one constant under the validity
        BigDecimal value = new BigDecimal(new BigInteger(unscaled), 2);
        BigDecimal[] values = new BigDecimal[1_000];
        Arrays.fill(values, value);
        values[3] = null;
        values[500] = null;

        // When
        Path file = write(precision, WriteOptions.defaults().withZoneMaps(false), values);

        // Then
        assertThat(read(file)).containsExactly(values);
        assertThat(fileSize(file)).isLessThan(2_000);
    }

    @ParameterizedTest(name = "DECIMAL({0}, 2), {1} rows")
    @CsvSource({"3, 1", "15, 1", "20, 1", "3, 2", "15, 4", "38, 2"})
    void fewEqualRows_stayPlain_whenTheConstantIsNotSmaller(int precision, int rows) throws Exception {
        // Given — a constant scalar costs about as much as one or two plain values
        BigDecimal[] values = new BigDecimal[rows];
        Arrays.fill(values, dec("1.25"));

        // When
        Path file = write(precision, WriteOptions.defaults().withZoneMaps(false), values);

        // Then
        assertThat(read(file)).containsExactly(values);
    }

    @ParameterizedTest(name = "nullable={0}")
    @ValueSource(booleans = {true, false})
    void wideValuesPastAnI64_roundTrip_asAPlainDecimal(boolean nullable) throws Exception {
        // Given — 34 digits: no 64-bit mantissa holds them, so the byte parts step aside
        BigDecimal big = new BigDecimal("1234567890123456789012345678901234.56");
        BigDecimal[] values = {big, dec("0.05"), big.negate(), nullable ? null : dec("2.00")};

        // When
        Path file = write(38, WriteOptions.defaults(), values, nullable);

        // Then
        assertThat(read(file)).containsExactly(values);
    }

    @ParameterizedTest(name = "DECIMAL({0}, 2)")
    @ValueSource(ints = {3, 15, 20, 38})
    void allNullDecimal_roundTrips(int precision) throws Exception {
        // Given
        BigDecimal[] values = new BigDecimal[100];

        // When
        Path file = write(precision, WriteOptions.defaults(), values);

        // Then
        assertThat(read(file)).containsExactly(values);
    }

    @ParameterizedTest(name = "DECIMAL({0}, 2)")
    @ValueSource(ints = {3, 15, 20, 38})
    void nullableDecimalWithoutNulls_roundTrips(int precision) throws Exception {
        // Given — a nullable dtype whose rows are all valid is written without a validity
        BigDecimal[] values = new BigDecimal[500];
        for (int i = 0; i < values.length; i++) {
            values[i] = new BigDecimal(BigInteger.valueOf(i * 7L - 1_000L), 2);
        }

        // When
        Path file = write(precision, WriteOptions.defaults(), values);

        // Then
        assertThat(read(file)).containsExactly(values);
        assertThat(stats(file).nullCount()).isZero();
    }

    private Path write(int precision, WriteOptions options, BigDecimal[] values) throws IOException {
        return write(precision, options, values, true);
    }

    private Path write(int precision, WriteOptions options, BigDecimal[] values, boolean nullable)
            throws IOException {
        boolean[] validity = new boolean[values.length];
        for (int i = 0; i < values.length; i++) {
            validity[i] = values[i] != null;
        }
        DType.Struct schema = new DType.Struct(List.of(D), List.of(DType.Decimal.of(precision, 2, nullable)), false);
        Path file = tmp.resolve("d-" + precision + "-" + System.nanoTime() + ".vortex");
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(channel, schema, options)) {
            writer.writeChunk(Map.of(D, nullable ? new NullableData(values, validity) : values));
        }
        return file;
    }

    private static ArrayStats stats(Path file) throws IOException {
        try (VortexReader reader = VortexReader.open(file)) {
            return reader.columnStats().get(D);
        }
    }

    private static BigDecimal[] read(Path file) throws IOException {
        List<BigDecimal> out = new ArrayList<>();
        try (VortexReader reader = VortexReader.open(file);
             ScanIterator scan = reader.scan(ScanOptions.all())) {
            while (scan.hasNext()) {
                try (Chunk chunk = scan.next()) {
                    Array column = chunk.column(D);
                    MaskedArray masked = column instanceof MaskedArray m ? m : null;
                    DecimalArray decimals = (DecimalArray) (masked == null ? column : masked.inner());
                    for (long i = 0; i < chunk.rowCount(); i++) {
                        out.add(masked != null && !masked.isValid(i) ? null : decimals.getDecimal(i));
                    }
                }
            }
        }
        return out.toArray(BigDecimal[]::new);
    }

    private static long fileSize(Path file) throws IOException {
        return java.nio.file.Files.size(file);
    }

    private static BigDecimal dec(String value) {
        return new BigDecimal(value);
    }

    /// The unscaled value of a decimal bound as `ArrayStats` reads it: a UTF-8 string of the
    /// little-endian two's-complement bytes, at whatever width the encoding wrote them.
    private static long unscaled(Object bound) {
        byte[] le = ((String) bound).getBytes(StandardCharsets.UTF_8);
        byte[] be = new byte[le.length];
        for (int i = 0; i < le.length; i++) {
            be[le.length - 1 - i] = le[i];
        }
        return new BigInteger(be).longValueExact();
    }
}
