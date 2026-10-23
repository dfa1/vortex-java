package io.github.dfa1.vortex.csv;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ScanIterator;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.writer.WriteOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ForkJoinPool;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

class CsvImporterTest {

    @Test
    void infersTypedColumnsAndRoundTrips(@TempDir Path tmp) throws Exception {
        // Given
        Path csv = tmp.resolve("data.csv");
        Files.writeString(csv, "id,price,active,name\n1,1.5,true,Alice\n2,2.7,false,Bob\n");
        Path vortex = tmp.resolve("data.vortex");

        // When
        CsvImporter.importCsv(csv, vortex);

        // Then
        try (VortexReader reader = VortexReader.open(vortex)) {
            assertThat(reader.dtype()).isInstanceOf(DType.Struct.class);
            DType.Struct schema = (DType.Struct) reader.dtype();
            assertThat(schema.fieldNames().stream().map(ColumnName::value).toList()).containsExactly("id", "price", "active", "name");
            // inferred numbers and booleans are nullable, text is not (#592)
            assertThat(schema.fieldTypes().get(0)).isEqualTo(DType.I64.asNullable());
            assertThat(schema.fieldTypes().get(1)).isEqualTo(DType.F64.asNullable());
            assertThat(schema.fieldTypes().get(2)).isEqualTo(DType.BOOL.asNullable());
            assertThat(schema.fieldTypes().get(3)).isEqualTo(DType.UTF8);

            try (ScanIterator iter = reader.scan(ScanOptions.all())) {
                assertThat(iter.hasNext()).isTrue();
                try (Chunk chunk = iter.next()) {
                    assertThat(chunk.rowCount()).isEqualTo(2);
                    LongArray ids = (LongArray) MaskedArray.unwrap(chunk.column(ColumnName.of("id"))).inner();
                    assertThat(ids.getLong(0)).isEqualTo(1L);
                    assertThat(ids.getLong(1)).isEqualTo(2L);
                    VarBinArray names = chunk.column(ColumnName.of("name"));
                    assertThat(names.getString(0)).isEqualTo("Alice");
                    assertThat(names.getString(1)).isEqualTo("Bob");
                }
            }
        }
    }

    @Test
    void usesCustomDelimiter(@TempDir Path tmp) throws Exception {
        // Given
        Path csv = tmp.resolve("data.csv");
        Files.writeString(csv, "id;name\n1;Alice\n2;Bob\n");
        Path vortex = tmp.resolve("data.vortex");

        // When
        CsvImporter.importCsv(csv, vortex, ImportOptions.defaults().withDelimiter(';').withWriteOptions(WriteOptions.defaults()));

        // Then
        try (VortexReader reader = VortexReader.open(vortex)) {
            DType.Struct schema = (DType.Struct) reader.dtype();
            assertThat(schema.fieldNames().stream().map(ColumnName::value).toList()).containsExactly("id", "name");
        }
    }

    @Test
    void generatesHeadersWhenMissing(@TempDir Path tmp) throws Exception {
        // Given
        Path csv = tmp.resolve("data.csv");
        Files.writeString(csv, "1,Alice\n2,Bob\n");
        Path vortex = tmp.resolve("data.vortex");

        // When
        CsvImporter.importCsv(csv, vortex, ImportOptions.defaults().withHeader(false).withWriteOptions(WriteOptions.defaults()));

        // Then
        try (VortexReader reader = VortexReader.open(vortex)) {
            DType.Struct schema = (DType.Struct) reader.dtype();
            assertThat(schema.fieldNames().stream().map(ColumnName::value).toList()).containsExactly("col0", "col1");
        }
    }

    @Test
    void respectsSchemaOverride(@TempDir Path tmp) throws Exception {
        // Given
        Path csv = tmp.resolve("data.csv");
        Files.writeString(csv, "value\n42\n99\n");
        Path vortex = tmp.resolve("data.vortex");
        DType.Struct forcedSchema = new DType.Struct(
                java.util.List.of(ColumnName.of("value")),
                java.util.List.of(DType.UTF8),
                false);

        // When
        CsvImporter.importCsv(csv, vortex, ImportOptions.defaults().withSchema(forcedSchema));

        // Then
        try (VortexReader reader = VortexReader.open(vortex)) {
            DType.Struct schema = (DType.Struct) reader.dtype();
            assertThat(schema.fieldTypes().getFirst()).isEqualTo(DType.UTF8);
            try (ScanIterator iter = reader.scan(ScanOptions.all())) {
                assertThat(iter.hasNext()).isTrue();
                try (Chunk chunk = iter.next()) {
                    VarBinArray values = chunk.column(ColumnName.of("value"));
                    assertThat(values.getString(0)).isEqualTo("42");
                }
            }
        }
    }

    @Test
    void emptyCellsInNumberAndBooleanColumnsAreNull(@TempDir Path tmp) throws Exception {
        // Given: qty and active have one empty cell, bc is empty everywhere, name has an empty
        // text cell (#592: the numbers and booleans were stored as 0 and false)
        String text = "id,qty,active,name,bc\n1,5,true,a,\n2,,,,\n3,0,false,c,\n";
        Path csv = tmp.resolve("empties.csv");
        Files.writeString(csv, text);
        Path vortex = tmp.resolve("empties.vortex");
        Path exported = tmp.resolve("exported.csv");

        // When
        CsvImporter.importCsv(csv, vortex);
        CsvExporter.exportCsv(vortex, exported, ExportOptions.defaults());

        // Then: numbers and booleans nullable, text keeps "", and a null exports as an empty cell
        // again, so the file round-trips where zeros would have read back as "0"
        try (VortexReader reader = VortexReader.open(vortex)) {
            DType.Struct schema = (DType.Struct) reader.dtype();
            assertThat(schema.fieldTypes()).containsExactly(DType.I64.asNullable(), DType.I64.asNullable(),
                    DType.BOOL.asNullable(), DType.UTF8, DType.I64.asNullable());
        }
        assertThat(Files.readAllLines(exported)).isEqualTo(text.lines().toList());
    }

    @Test
    void anEmptyCellAfterTheInferredRowsIsNull(@TempDir Path tmp) throws Exception {
        // Given: qty has no empty cell in the rows types are inferred from, then one in the last
        // row; it must still be a null, not a 0 and not a failed import
        int rows = ImportOptions.defaults().chunkSize() + 10;
        StringBuilder text = new StringBuilder("id,qty\n");
        for (int i = 0; i < rows; i++) {
            text.append(i).append(',').append(i == rows - 1 ? "" : String.valueOf(i % 9)).append('\n');
        }
        Path csv = tmp.resolve("late-empty.csv");
        Files.writeString(csv, text);
        Path vortex = tmp.resolve("late-empty.vortex");
        Path exported = tmp.resolve("late-empty-exported.csv");

        // When
        CsvImporter.importCsv(csv, vortex);
        CsvExporter.exportCsv(vortex, exported, ExportOptions.defaults());

        // Then
        assertThat(Files.readAllLines(exported)).isEqualTo(text.toString().lines().toList());
    }

    @ParameterizedTest
    @MethodSource("numberAndBooleanTypes")
    void anEmptyCellInANonNullableSchemaColumnFails(DType type, @TempDir Path tmp) throws Exception {
        // Given: a quoted "" is an empty cell (a bare empty line in a one-column file is skipped);
        // a non-nullable number or boolean has no value to store for it (#592: it stored 0 or false)
        Path csv = tmp.resolve("schema.csv");
        Files.writeString(csv, "qty\n1\n\"\"\n");
        ImportOptions options = ImportOptions.defaults().withSchema(DType.structBuilder()
                .field(ColumnName.of("qty"), type)
                .build());

        // When
        Throwable result = catchThrowable(() -> CsvImporter.importCsv(csv, tmp.resolve("schema.vortex"), options));

        // Then
        assertThat(result).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("column 'qty' has an empty cell");
    }

    @ParameterizedTest
    @MethodSource("numberAndBooleanTypes")
    void aSchemaColumnTheFileLacksIsNullWhenNullable(DType type, @TempDir Path tmp) throws Exception {
        // Given: the schema has one more column than the file, which is empty in every row
        Path csv = tmp.resolve("short.csv");
        Files.writeString(csv, "id\n1\n2\n");
        Path vortex = tmp.resolve("short.vortex");
        ImportOptions options = ImportOptions.defaults().withSchema(DType.structBuilder()
                .field(ColumnName.of("id"), DType.I64)
                .field(ColumnName.of("extra"), type.asNullable())
                .build());

        // When
        CsvImporter.importCsv(csv, vortex, options);

        // Then: every row of the extra column is null
        try (VortexReader reader = VortexReader.open(vortex);
             ScanIterator iter = reader.scan(ScanOptions.all());
             Chunk chunk = iter.next()) {
            MaskedArray.Unwrapped result = MaskedArray.unwrap(chunk.column(ColumnName.of("extra")));
            assertThat(result.validity()).isNotNull();
            assertThat(result.validity().getBoolean(0)).isFalse();
            assertThat(result.validity().getBoolean(1)).isFalse();
        }
    }

    @ParameterizedTest
    @MethodSource("numberAndBooleanTypes")
    void aSchemaColumnTheFileLacksFailsWhenNotNullable(DType type, @TempDir Path tmp) throws Exception {
        // Given: before #592 the missing column was written as zeros or false
        Path csv = tmp.resolve("short.csv");
        Files.writeString(csv, "id\n1\n2\n");
        ImportOptions options = ImportOptions.defaults().withSchema(DType.structBuilder()
                .field(ColumnName.of("id"), DType.I64)
                .field(ColumnName.of("extra"), type)
                .build());

        // When
        Throwable result = catchThrowable(() -> CsvImporter.importCsv(csv, tmp.resolve("short.vortex"), options));

        // Then
        assertThat(result).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("the CSV has no column for 'extra'");
    }

    @Test
    void aNullableTextSchemaStoresEmptyCellsAsNull(@TempDir Path tmp) throws Exception {
        // Given: inferred text keeps "", but a schema asking for nullable text gets null
        Path csv = tmp.resolve("text.csv");
        Files.writeString(csv, "id,name\n1,a\n2,\n");
        Path vortex = tmp.resolve("text.vortex");
        ImportOptions options = ImportOptions.defaults().withSchema(DType.structBuilder()
                .field(ColumnName.of("id"), DType.I64)
                .field(ColumnName.of("name"), DType.UTF8.asNullable())
                .build());

        // When
        CsvImporter.importCsv(csv, vortex, options);

        // Then
        try (VortexReader reader = VortexReader.open(vortex);
             ScanIterator iter = reader.scan(ScanOptions.all());
             Chunk chunk = iter.next()) {
            MaskedArray.Unwrapped name = MaskedArray.unwrap(chunk.column(ColumnName.of("name")));
            assertThat(name.validity()).isNotNull();
            assertThat(name.validity().getBoolean(0)).isTrue();
            assertThat(name.validity().getBoolean(1)).isFalse();
        }
    }

    @Test
    void chunksWrittenInTheBackgroundKeepFileOrder(@TempDir Path tmp) throws Exception {
        // Given: two full chunks and a partial one, so each is converted while the next is parsed
        int rows = 2 * ImportOptions.defaults().chunkSize() + 123;
        StringBuilder text = new StringBuilder("id\n");
        for (int i = 0; i < rows; i++) {
            text.append(i).append('\n');
        }
        Path csv = tmp.resolve("ordered.csv");
        Files.writeString(csv, text);
        Path vortex = tmp.resolve("ordered.vortex");

        // When
        CsvImporter.importCsv(csv, vortex);

        // Then: every row back once, in file order
        long expected = 0;
        try (VortexReader reader = VortexReader.open(vortex);
             ScanIterator iter = reader.scan(ScanOptions.all())) {
            while (iter.hasNext()) {
                try (Chunk chunk = iter.next()) {
                    LongArray ids = (LongArray) MaskedArray.unwrap(chunk.column(ColumnName.of("id"))).inner();
                    for (int i = 0; i < chunk.rowCount(); i++) {
                        assertThat(ids.getLong(i)).isEqualTo(expected++);
                    }
                }
            }
        }
        assertThat(expected).isEqualTo(rows);
    }

    @Test
    void aBadValueAfterTheFirstChunkFailsWithItsOwnException(@TempDir Path tmp) throws Exception {
        // Given: the first chunk infers a long column; a later chunk, converted on the background
        // thread, holds a value that is not one
        int rows = ImportOptions.defaults().chunkSize() + 10;
        StringBuilder text = new StringBuilder("qty\n");
        for (int i = 0; i < rows; i++) {
            text.append(i == rows - 1 ? "N/A" : String.valueOf(i)).append('\n');
        }
        Path csv = tmp.resolve("late.csv");
        Files.writeString(csv, text);
        Path vortex = tmp.resolve("late.vortex");

        // When
        Throwable result = catchThrowable(() -> CsvImporter.importCsv(csv, vortex));

        // Then: the conversion's own exception, not the executor's wrapper around it
        assertThat(result).isInstanceOf(NumberFormatException.class).hasMessageContaining("N/A");
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-0", "+5", "-42", "123456789012345678", "1234567890123456789",
            "9223372036854775807", "-9223372036854775808", "9223372036854775808", "1_0", " 1", "1e3",
            "-", "+", "--1", "١٢٣", "12a"})
    void parseLong_agreesWithLongParseLong(String text) {
        // Given: 18 digits stay on the byte path, longer, signed-only and non-ASCII digits fall back
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        Object expected = parsed(() -> Long.parseLong(text));

        // When
        Object result = parsed(() -> CsvImporter.parseLong(bytes, 0, bytes.length));

        // Then
        assertThat(result).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-0", "-0.0", "+1.5", "364.99", "007.50", "1.", ".5", ".", "-", "1.2.3", "1e3",
            " 1.5", "NaN", "Infinity", "0x1p3", "1.5d", "9007199254740993", "9007199254740992.5",
            "123456789012345678", "1234567890123456789", "0.1234567890123456789012", "3.14159265358979323846"})
    void parseDouble_agreesWithDoubleParseDouble(String text) {
        // Given: the fast path's edges (2^53, 22 fraction digits, 18 digits) and everything it hands back
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        Object expected = parsedDouble(() -> Double.parseDouble(text));

        // When
        Object result = parsedDouble(() -> CsvImporter.parseDouble(bytes, 0, bytes.length));

        // Then
        assertThat(result).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(longs = {1, 2, 3, 4, 5})
    void parseDouble_agreesWithDoubleParseDoubleOnRandomDecimals(long seed) {
        // Given: decimals of up to 18 digits with up to 22 fraction digits, bit-for-bit
        java.util.Random random = new java.util.Random(seed);
        for (int k = 0; k < 4_000; k++) {
            long digits = random.nextLong() >>> (1 + random.nextInt(63));
            int fraction = random.nextInt(23);
            String text = new java.math.BigDecimal(java.math.BigInteger.valueOf(digits), fraction).toPlainString();
            if (random.nextBoolean()) {
                text = "-" + text;
            }
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);

            // When
            double result = CsvImporter.parseDouble(bytes, 0, bytes.length);

            // Then
            assertThat(Double.doubleToRawLongBits(result)).as(text)
                    .isEqualTo(Double.doubleToRawLongBits(Double.parseDouble(text)));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "a", "abcdefg", "abcdefgh", "abcdefghi", "abcdefghé", "éabcdefghijklmnop"})
    void isAscii_findsAHighByteAnywhere(String text) {
        // Given: lengths around the 8-byte word, the high byte in the word part and in the tail
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);

        // When
        boolean result = CsvImporter.isAscii(bytes, 0, bytes.length);

        // Then
        assertThat(result).isEqualTo(text.chars().allMatch(c -> c < 0x80));
    }

    @ParameterizedTest
    @ValueSource(strings = {"true", "TRUE", "tRuE", "true ", "false", "t", "truee", "trué", "true"})
    void parseBoolean_agreesWithBooleanParseBoolean(String text) {
        // Given
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        boolean expected = Boolean.parseBoolean(text);

        // When
        boolean result = CsvImporter.parseBoolean(bytes, 0, bytes.length);

        // Then
        assertThat(result).isEqualTo(expected);
    }

    @Test
    void utf8_replacesMalformedBytesAsTheDecoderDoes() {
        // Given: a lone lead byte between ASCII, which FastCSV's decoding turned into U+FFFD
        byte[] bytes = {'a', (byte) 0xc3, 'b'};

        // When
        byte[] result = CsvImporter.utf8(bytes, 0, bytes.length);

        // Then
        assertThat(new String(result, StandardCharsets.UTF_8)).isEqualTo("a�b");
    }

    @Test
    void buildChunkOnAnExecutorKeepsTheColumnsAndTheirOrder() throws IOException {
        // Given: a column of each kind, so every conversion path runs off the caller thread
        DType.Struct schema = DType.structBuilder()
                .field(ColumnName.of("id"), DType.I64)
                .field(ColumnName.of("price"), DType.F64)
                .field(ColumnName.of("name"), DType.UTF8)
                .build();
        ColumnChunk rows = new ColumnChunk(2);
        try (CsvByteReader reader = new CsvByteReader(
                new ByteArrayInputStream("1,1.5,a\n2,2.5,b\n".getBytes(StandardCharsets.UTF_8)), ',')) {
            while (reader.readRecord(rows)) {
                // read both rows
            }
        }

        // When
        var result = CsvImporter.buildChunk(schema, rows, ForkJoinPool.commonPool());

        // Then: strings come out as their UTF-8 bytes, which the writer takes for a Utf8 column
        assertThat(result.keySet()).extracting(ColumnName::value).containsExactly("id", "price", "name");
        assertThat((long[]) result.get(ColumnName.of("id"))).containsExactly(1L, 2L);
        assertThat((double[]) result.get(ColumnName.of("price"))).containsExactly(1.5, 2.5);
        assertThat((byte[][]) result.get(ColumnName.of("name"))).isDeepEqualTo(new byte[][]{
                "a".getBytes(StandardCharsets.UTF_8), "b".getBytes(StandardCharsets.UTF_8)});
    }

    /// The parsed value's bits, or the exception's class and message when parsing fails.
    private static Object parsedDouble(DoubleSupplier parse) {
        try {
            return Double.doubleToRawLongBits(parse.getAsDouble());
        } catch (NumberFormatException e) {
            return e.getClass().getName() + ": " + e.getMessage();
        }
    }

    /// The parsed value, or the exception's class and message when parsing fails.
    private static Object parsed(LongSupplier parse) {
        try {
            return parse.getAsLong();
        } catch (NumberFormatException e) {
            return e.getClass().getName() + ": " + e.getMessage();
        }
    }

    /// The column types that have no stand-in for an empty cell: every type but text.
    private static Stream<DType> numberAndBooleanTypes() {
        return Stream.of(DType.I64, DType.F64, DType.BOOL);
    }
}
