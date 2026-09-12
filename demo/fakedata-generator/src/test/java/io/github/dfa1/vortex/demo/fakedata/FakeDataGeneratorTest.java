package io.github.dfa1.vortex.demo.fakedata;

import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FakeDataGeneratorTest {

    @Test
    void generatesAnExactArithmeticSeries(@TempDir Path dir) throws IOException {
        // Given a single series column
        List<ColumnDescriptor> columns = List.of(DescriptorParser.parse("id:i64:series(10,5)"));
        Path out = dir.resolve("series.vortex");

        // When
        FakeDataGenerator.generate(columns, 6, 42L, null, 65_536, 3, out);

        // Then rows are the exact arithmetic progression: 10, 15, 20, 25, 30, 35
        List<Long> values = readLongColumn(out, "id");
        assertThat(values).containsExactly(10L, 15L, 20L, 25L, 30L, 35L);
    }

    @Test
    void generatesEnumLabelsWithinTheDeclaredCount(@TempDir Path dir) throws IOException {
        // Given an enum column with 3 labels
        List<ColumnDescriptor> columns = List.of(DescriptorParser.parse("symbol:utf8:enum(SYM,3)"));
        Path out = dir.resolve("enum.vortex");

        // When
        FakeDataGenerator.generate(columns, 200, 7L, null, 65_536, 3, out);

        // Then every value is one of the 3 declared labels
        List<String> values = readUtf8Column(out, "symbol");
        assertThat(values).hasSize(200).allSatisfy(v -> assertThat(v).isIn("SYM0", "SYM1", "SYM2"));
    }

    @Test
    void sortByOrdersEveryColumnByTheSamePermutation(@TempDir Path dir) throws IOException {
        // Given a series id column (its natural order reveals the sort permutation) and an
        // enum symbol column, sorted by symbol
        List<ColumnDescriptor> columns = List.of(
                DescriptorParser.parse("id:i64:series(0,1)"),
                DescriptorParser.parse("symbol:utf8:enum(SYM,4)"));
        Path out = dir.resolve("sorted.vortex");

        // When
        FakeDataGenerator.generate(columns, 500, 1L, "symbol", 65_536, 3, out);

        // Then the symbol column is non-decreasing...
        List<String> symbols = readUtf8Column(out, "symbol");
        assertThat(symbols).isSorted();
        // ...and each id still names the row that originally held that symbol (id i's symbol
        // before sorting is deterministic from the same seed/generator, so this cross-checks
        // that every column moved together under the same permutation, not just "symbol" itself).
        List<ColumnDescriptor> unsorted = List.of(
                DescriptorParser.parse("id:i64:series(0,1)"),
                DescriptorParser.parse("symbol:utf8:enum(SYM,4)"));
        Path unsortedOut = dir.resolve("unsorted.vortex");
        FakeDataGenerator.generate(unsorted, 500, 1L, null, 65_536, 3, unsortedOut);
        List<String> originalSymbolById = readUtf8Column(unsortedOut, "symbol");

        List<Long> ids = readLongColumn(out, "id");
        for (int i = 0; i < ids.size(); i++) {
            assertThat(symbols.get(i)).isEqualTo(originalSymbolById.get(ids.get(i).intValue()));
        }
    }

    @Test
    void generatesConstantAndBoolColumns(@TempDir Path dir) throws IOException {
        // Given a constant and a random-bool column
        List<ColumnDescriptor> columns = List.of(
                DescriptorParser.parse("flag:bool:constant(true)"),
                DescriptorParser.parse("active:bool:bool()"));
        Path out = dir.resolve("bools.vortex");

        // When
        FakeDataGenerator.generate(columns, 50, 3L, null, 65_536, 3, out);

        // Then
        List<Boolean> flags = readBoolColumn(out, "flag");
        assertThat(flags).hasSize(50).containsOnly(true);
    }

    @Test
    void writesMultipleChunksWhenRowsExceedChunkSize(@TempDir Path dir) throws IOException {
        // Given a chunk size smaller than the row count
        List<ColumnDescriptor> columns = List.of(DescriptorParser.parse("id:i64:series(0,1)"));
        Path out = dir.resolve("chunked.vortex");

        // When
        FakeDataGenerator.generate(columns, 1000, 42L, null, 100, 3, out);

        // Then all rows are still present, in order, across chunk boundaries
        List<Long> values = readLongColumn(out, "id");
        assertThat(values).hasSize(1000);
        for (long i = 0; i < 1000; i++) {
            assertThat(values.get((int) i)).isEqualTo(i);
        }
    }

    private static List<Long> readLongColumn(Path file, String column) throws IOException {
        List<Long> out = new ArrayList<>();
        try (VortexReader vf = VortexReader.open(file); var iter = vf.scan(ScanOptions.all())) {
            while (iter.hasNext()) {
                try (var chunk = iter.next()) {
                    LongArray array = chunk.column(column);
                    for (long i = 0; i < array.length(); i++) {
                        out.add(array.getLong(i));
                    }
                }
            }
        }
        return out;
    }

    private static List<String> readUtf8Column(Path file, String column) throws IOException {
        List<String> out = new ArrayList<>();
        try (VortexReader vf = VortexReader.open(file); var iter = vf.scan(ScanOptions.all())) {
            while (iter.hasNext()) {
                try (var chunk = iter.next()) {
                    VarBinArray array = chunk.column(column);
                    for (long i = 0; i < array.length(); i++) {
                        out.add(new String(array.getBytes(i)));
                    }
                }
            }
        }
        return out;
    }

    private static List<Boolean> readBoolColumn(Path file, String column) throws IOException {
        List<Boolean> out = new ArrayList<>();
        try (VortexReader vf = VortexReader.open(file); var iter = vf.scan(ScanOptions.all())) {
            while (iter.hasNext()) {
                try (var chunk = iter.next()) {
                    BoolArray array = chunk.column(column);
                    for (long i = 0; i < array.length(); i++) {
                        out.add(array.getBoolean(i));
                    }
                }
            }
        }
        return out;
    }
}
