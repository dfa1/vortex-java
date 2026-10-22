package io.github.dfa1.vortex.csv;

import de.siegmar.fastcsv.reader.CsvReader;
import de.siegmar.fastcsv.reader.StringArrayHandler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Random;
import java.util.stream.LongStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

/// CsvByteReader replaced FastCSV in the import, so it must read every file the way FastCSV's
/// default reader did. FastCSV is the oracle: random files mixing quoted fields, escaped quotes,
/// every line ending, empty lines, multi-byte and malformed UTF-8, read by both.
class CsvByteReaderTest {

    static LongStream seeds() {
        return LongStream.rangeClosed(1, 200);
    }

    @ParameterizedTest
    @MethodSource("seeds")
    void readsRecordsAsFastCsv(long seed) {
        // Given: a file FastCSV rejects must fail in both
        Random random = new Random(seed);
        char delimiter = random.nextBoolean() ? ',' : ';';
        byte[] csv = randomCsv(random, delimiter);
        Object expected = outcome(() -> fastCsv(csv, delimiter));

        // When: a tiny read size, so records and quotes straddle refills
        Object result = outcome(() -> byteReader(new TrickleInputStream(csv, 1 + random.nextInt(7)), delimiter));

        // Then
        assertThat(result).as(HexFormat.of().formatHex(csv)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "a,b\n1,2,3\n",         // more fields than the first record
            "a,b\n1\n",             // fewer
            "a,b\n\"x\"y,2\n",      // text after a closing quote
    })
    void rejectsWhatFastCsvRejects(String csv) {
        // Given
        byte[] bytes = csv.getBytes(StandardCharsets.UTF_8);
        Throwable fastCsvFailure = catchThrowable(() -> fastCsv(bytes, ','));

        // When
        Throwable result = catchThrowable(() -> byteReader(new ByteArrayInputStream(bytes), ','));

        // Then
        assertThat(fastCsvFailure).as("FastCSV rejects it too").isNotNull();
        assertThat(result).isInstanceOf(IOException.class);
    }

    @Test
    void aNonAsciiDelimiterIsRefused() {
        // When
        Throwable result = catchThrowable(() -> new CsvByteReader(InputStream.nullInputStream(), '§'));

        // Then: a delimiter byte inside a multi-byte character would split it
        assertThat(result).isInstanceOf(IllegalArgumentException.class);
    }

    private static byte[] randomCsv(Random random, char delimiter) {
        String[] pieces = {"a", "bc", "1", "-2.5", " ", "\"", "\n", "\r", String.valueOf(delimiter), "é", "€", "😀", ""};
        String[] endings = {"\n", "\r\n", "\r"};
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (random.nextInt(10) == 0) {
            out.writeBytes(new byte[]{(byte) 0xef, (byte) 0xbb, (byte) 0xbf});
        }
        int columns = 1 + random.nextInt(4);
        int records = random.nextInt(30);
        for (int r = 0; r < records; r++) {
            if (random.nextInt(8) == 0) {
                out.writeBytes(endings[random.nextInt(endings.length)].getBytes(StandardCharsets.UTF_8));
            }
            for (int c = 0; c < columns; c++) {
                if (c > 0) {
                    out.write(delimiter);
                }
                StringBuilder field = new StringBuilder();
                for (int k = random.nextInt(4); k > 0; k--) {
                    field.append(pieces[random.nextInt(pieces.length)]);
                }
                String text = field.toString();
                boolean mustQuote = text.indexOf(delimiter) >= 0 || text.contains("\"")
                        || text.contains("\n") || text.contains("\r");
                if (mustQuote || random.nextInt(5) == 0) {
                    out.writeBytes(("\"" + text.replace("\"", "\"\"") + "\"").getBytes(StandardCharsets.UTF_8));
                } else {
                    out.writeBytes(text.getBytes(StandardCharsets.UTF_8));
                    if (random.nextInt(10) == 0) {
                        // malformed UTF-8 ending the field: a lone lead byte, or a stray continuation
                        out.write(random.nextBoolean() ? 0xc3 : 0x80);
                    }
                }
            }
            if (r < records - 1 || random.nextBoolean()) {
                out.writeBytes(endings[random.nextInt(endings.length)].getBytes(StandardCharsets.UTF_8));
            }
        }
        return out.toByteArray();
    }

    /// The records read, or `"rejected"` when reading fails: which exception differs by reader.
    private static Object outcome(Read read) {
        try {
            return read.records();
        } catch (IOException | RuntimeException _) {
            return "rejected";
        }
    }

    @FunctionalInterface
    private interface Read {
        List<List<String>> records() throws IOException;
    }

    private static List<List<String>> fastCsv(byte[] csv, char delimiter) throws IOException {
        List<List<String>> records = new ArrayList<>();
        try (CsvReader<String[]> reader = CsvReader.builder().fieldSeparator(delimiter)
                .build(StringArrayHandler.of(), new ByteArrayInputStream(csv))) {
            for (String[] fields : reader) {
                records.add(List.of(fields));
            }
        }
        return records;
    }

    private static List<List<String>> byteReader(InputStream csv, char delimiter) throws IOException {
        ColumnChunk chunk = new ColumnChunk(10_000);
        try (CsvByteReader reader = new CsvByteReader(csv, delimiter)) {
            while (reader.readRecord(chunk)) {
                // read every record
            }
        }
        List<List<String>> records = new ArrayList<>();
        for (int row = 0; row < chunk.rows(); row++) {
            List<String> fields = new ArrayList<>();
            for (int c = 0; c < chunk.columns(); c++) {
                fields.add(chunk.text(c, row));
            }
            records.add(fields);
        }
        return records;
    }

    /// Returns at most `step` bytes per read, as a slow network body would.
    private static final class TrickleInputStream extends ByteArrayInputStream {
        private final int step;

        TrickleInputStream(byte[] bytes, int step) {
            super(bytes);
            this.step = step;
        }

        @Override
        public synchronized int read(byte[] b, int off, int len) {
            return super.read(b, off, Math.min(len, step));
        }
    }
}
