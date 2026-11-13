package io.github.dfa1.vortex.cli;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.core.model.DTypes;
import io.github.dfa1.vortex.core.model.PType;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ScanIterator;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.DoubleArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static io.github.dfa1.vortex.cli.CliTestSupport.capture;
import static org.assertj.core.api.Assertions.assertThat;

class RewriteCommandTest {

    private static final ColumnName ID = ColumnName.of("id");
    private static final ColumnName QTY = ColumnName.of("qty");
    private static final ColumnName PRICE = ColumnName.of("price");
    private static final ColumnName NAME = ColumnName.of("name");
    private static final ColumnName FLAG = ColumnName.of("flag");

    @Test
    void wrongArity_returnsUsageError() {
        // Given / When
        CliTestSupport.Captured result = capture(() -> RewriteCommand.run(new String[]{"rewrite", "only-one"}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.USAGE_ERROR);
        assertThat(result.stderr()).contains("usage:");
    }

    @Test
    void existingOutput_isNotOverwritten(@TempDir Path tmp) throws IOException {
        // Given — overwriting would destroy the source if the two paths were the same file
        Path source = writeSource(tmp, 10);

        // When
        CliTestSupport.Captured result = capture(() ->
                RewriteCommand.run(new String[]{"rewrite", source.toString(), source.toString()}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.USAGE_ERROR);
        assertThat(result.stderr()).contains("refusing to overwrite");
        assertThat(Files.size(source)).isPositive();
    }

    @Test
    void missingSource_returnsFileNotFound(@TempDir Path tmp) {
        // Given
        Path missing = tmp.resolve("nope.vortex");

        // When
        CliTestSupport.Captured result = capture(() ->
                RewriteCommand.run(new String[]{"rewrite", missing.toString(), tmp.resolve("out.vortex").toString()}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.FILE_NOT_FOUND);
    }

    static Stream<List<String>> optionSets() {
        return Stream.of(
                List.of(),
                List.of("--compact"),
                List.of("--no-zone-maps"),
                List.of("--no-global-dict"),
                List.of("--edition", "core2025.10.0"),
                List.of("--edition=core2025.10.0"),
                List.of("--compact", "--no-zone-maps", "--no-global-dict", "--edition", "core2026.08.0"));
    }

    @ParameterizedTest
    @MethodSource("optionSets")
    void rewrite_keepsEveryValueAndNull_whateverTheOptions(List<String> options, @TempDir Path tmp) throws IOException {
        // Given — nullable and non-nullable columns of every supported family, with nulls in the
        // nullable ones, so a dropped validity or a shifted row shows up as a value mismatch
        int rows = 5_000;
        Path source = writeSource(tmp, rows);
        Path output = tmp.resolve("out.vortex");
        List<String> command = new ArrayList<>(List.of("rewrite"));
        command.addAll(options);
        command.addAll(List.of(source.toString(), output.toString()));
        String[] args = command.toArray(String[]::new);

        // When
        CliTestSupport.Captured result = capture(() -> RewriteCommand.run(args));

        // Then
        assertThat(result.status()).as(result.stderr()).isEqualTo(ExitStatus.OK);
        assertThat(result.stdout()).contains("written:").containsPattern("5\\D000 rows");
        assertThat(longs(output, ID)).isEqualTo(longs(source, ID));
        assertThat(longs(output, QTY)).isEqualTo(longs(source, QTY));
        assertThat(doubles(output, PRICE)).isEqualTo(doubles(source, PRICE));
        assertThat(strings(output, NAME)).isEqualTo(strings(source, NAME));
        assertThat(bools(output, FLAG)).isEqualTo(bools(source, FLAG));
        assertThat(strings(output, NAME)).containsNull();
        assertThat(longs(output, QTY)).containsNull();
    }

    @Test
    void rewrite_keepsMultiByteTextAndNulls(@TempDir Path tmp) throws IOException {
        // Given — 2-, 3- and 4-byte sequences, an empty string and a null: text is copied as bytes, so
        // none may be cut or re-encoded. (Invalid UTF-8 cannot be a source here: the writer rejects it.)
        DType.Struct schema = new DType.Struct(List.of(NAME), List.<DType>of(new DType.Utf8(true)), false);
        String[] values = {"héllo", "日本語", "😀 ok", "", null, "plain"};
        Path source = tmp.resolve("text.vortex");
        try (FileChannel ch = FileChannel.open(source, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of(NAME, values));
        }
        Path output = tmp.resolve("out.vortex");

        // When
        CliTestSupport.Captured result = capture(() ->
                RewriteCommand.run(new String[]{"rewrite", source.toString(), output.toString()}));

        // Then
        assertThat(result.status()).as(result.stderr()).isEqualTo(ExitStatus.OK);
        assertThat(strings(output, NAME)).containsExactly(values);
    }

    @ParameterizedTest
    @ValueSource(strings = {"--edition core1999.01.0", "--edition nonsense", "--edition=nonsense", "--edition --compact"})
    void unknownEdition_returnsUsageErrorListingTheKnownOnes(String edition, @TempDir Path tmp) throws IOException {
        // Given
        Path source = writeSource(tmp, 10);
        List<String> command = new ArrayList<>(List.of("rewrite"));
        command.addAll(List.of(edition.split(" ")));
        command.addAll(List.of(source.toString(), tmp.resolve("out.vortex").toString()));

        // When
        CliTestSupport.Captured result = capture(() -> RewriteCommand.run(command.toArray(String[]::new)));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.USAGE_ERROR);
        assertThat(result.stderr()).contains("--edition needs one of:").contains("core2026.08.0");
        assertThat(tmp.resolve("out.vortex")).doesNotExist();
    }

    @Test
    void editionTooOldForTheData_failsAndLeavesNoOutput(@TempDir Path tmp) throws IOException {
        // Given — a nullable utf8 column is written as vortex.masked, which joins core2025.10.0
        Path source = writeSource(tmp, 10);
        Path output = tmp.resolve("out.vortex");

        // When
        CliTestSupport.Captured result = capture(() -> RewriteCommand.run(new String[]{
                "rewrite", "--edition", "core2025.05.0", source.toString(), output.toString()}));

        // Then — the edition guard's own message says which edition to enable, and no partial file stays
        assertThat(result.status()).isEqualTo(ExitStatus.ERROR);
        assertThat(result.stderr()).contains("vortex.masked").contains("core2025.10.0");
        assertThat(output).doesNotExist();
    }

    @Test
    void editionWithoutValue_returnsUsageError() {
        // Given / When
        CliTestSupport.Captured result = capture(() -> RewriteCommand.run(new String[]{"rewrite", "a", "--edition"}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.USAGE_ERROR);
    }

    @Test
    void unsupportedColumn_failsBeforeCreatingTheOutput(@TempDir Path tmp) throws IOException {
        // Given — a decimal column: rewriting it as anything else would silently change the data
        DType.Struct schema = new DType.Struct(List.of(ID), List.<DType>of(new DType.Decimal((byte) 10, (byte) 2, false)), false);
        Path source = tmp.resolve("decimal.vortex");
        try (FileChannel ch = FileChannel.open(source, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of(ID, new java.math.BigDecimal[]{java.math.BigDecimal.ONE}));
        }
        Path output = tmp.resolve("out.vortex");

        // When
        CliTestSupport.Captured result = capture(() ->
                RewriteCommand.run(new String[]{"rewrite", source.toString(), output.toString()}));

        // Then
        assertThat(result.status()).isEqualTo(ExitStatus.ERROR);
        assertThat(result.stderr()).contains("does not support column 'id'");
        assertThat(output).doesNotExist();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Path writeSource(Path dir, int rows) throws IOException {
        DType.Struct schema = new DType.Struct(
                List.of(ID, QTY, PRICE, NAME, FLAG),
                List.of(DTypes.I64, new DType.Primitive(PType.I32, true), DTypes.F64, new DType.Utf8(true), DTypes.BOOL),
                false);
        long[] ids = new long[rows];
        Integer[] qty = new Integer[rows];
        double[] price = new double[rows];
        String[] name = new String[rows];
        boolean[] flag = new boolean[rows];
        for (int i = 0; i < rows; i++) {
            ids[i] = 1_000L * i;
            qty[i] = i % 7 == 0 ? null : i % 100;
            price[i] = i / 4.0;
            name[i] = i % 5 == 0 ? null : "name-" + (i % 50);
            flag[i] = i % 3 == 0;
        }
        Path file = dir.resolve("source.vortex");
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             VortexWriter writer = VortexWriter.create(ch, schema, WriteOptions.defaults())) {
            writer.writeChunk(Map.of(ID, ids, QTY, qty, PRICE, price, NAME, name, FLAG, flag));
        }
        return file;
    }

    private static List<Long> longs(Path file, ColumnName column) throws IOException {
        List<Long> out = new ArrayList<>();
        scan(file, column, (array, validity, i) -> {
            if (array instanceof LongArray a) {
                out.add(valid(validity, i) ? a.getLong(i) : null);
            } else {
                out.add(valid(validity, i) ? (long) ((IntArray) array).getInt(i) : null);
            }
        });
        return out;
    }

    private static List<Double> doubles(Path file, ColumnName column) throws IOException {
        List<Double> out = new ArrayList<>();
        scan(file, column, (array, validity, i) -> out.add(valid(validity, i) ? ((DoubleArray) array).getDouble(i) : null));
        return out;
    }

    private static List<String> strings(Path file, ColumnName column) throws IOException {
        List<String> out = new ArrayList<>();
        scan(file, column, (array, validity, i) -> out.add(valid(validity, i) ? ((VarBinArray) array).getString(i) : null));
        return out;
    }

    private static List<Boolean> bools(Path file, ColumnName column) throws IOException {
        List<Boolean> out = new ArrayList<>();
        scan(file, column, (array, validity, i) -> out.add(valid(validity, i) ? ((BoolArray) array).getBoolean(i) : null));
        return out;
    }

    private static boolean valid(BoolArray validity, long i) {
        return validity == null || validity.getBoolean(i);
    }

    private static void scan(Path file, ColumnName column, RowVisitor visitor) throws IOException {
        try (VortexReader reader = VortexReader.open(file);
             ScanIterator scan = reader.scan(ScanOptions.all())) {
            while (scan.hasNext()) {
                try (Chunk chunk = scan.next()) {
                    MaskedArray.Unwrapped unwrapped = MaskedArray.unwrap(chunk.column(column));
                    for (long i = 0; i < chunk.rowCount(); i++) {
                        visitor.visit(unwrapped.inner(), unwrapped.validity(), i);
                    }
                }
            }
        }
    }

    @FunctionalInterface
    private interface RowVisitor {
        void visit(io.github.dfa1.vortex.reader.array.Array array, BoolArray validity, long row);
    }
}
