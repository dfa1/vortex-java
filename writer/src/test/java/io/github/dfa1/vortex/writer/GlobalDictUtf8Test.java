package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.MaskedArray;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static io.github.dfa1.vortex.writer.VortexReads.readAllStrings;
import static org.assertj.core.api.Assertions.assertThat;

/// Global dictionary encoding for low-cardinality Utf8 columns: one dict shared
/// across all chunks instead of per-chunk dictionaries.
class GlobalDictUtf8Test {

    private static final DType.Struct SCHEMA = new DType.Struct(
            List.of(ColumnName.of("status")),
            List.of(DType.UTF8),
            false);

    @Test
    void lowCardinality_utf8_acrossChunks_usesGlobalDict(@TempDir Path tmp) throws IOException {
        // Given — 3 distinct values repeated across multiple chunks; cycling produces
        // 50/50 distribution well below the < 50% unique gate.
        Path file = tmp.resolve("status.vortex");
        String[] dict = {"open", "closed", "delivered"};
        int rowsPerChunk = 1_000;
        int chunkCount = 5;

        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.cascading(3))) {
            // When
            for (int c = 0; c < chunkCount; c++) {
                String[] data = new String[rowsPerChunk];
                for (int i = 0; i < rowsPerChunk; i++) {
                    data[i] = dict[(c + i) % dict.length];
                }
                sut.writeChunk(Map.of(ColumnName.of("status"), data));
            }
        }

        // Then — file is tiny (one shared dict of 3 strings ~30 bytes + N codes of U8).
        // 5 chunks × 1000 rows × U8 codes ≈ 5 KB + 30-byte dict + overhead.
        long size = Files.size(file);
        assertThat(size).as("global dict for 5 chunks of 1000 strings").isLessThan(8_000L);

        // And values round-trip exactly across all chunks.
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            List<String> got = readAllStrings(vf, "status");
            assertThat(got).hasSize(rowsPerChunk * chunkCount);
            // Spot-check: first row of chunk c starts with dict[c % 3], etc.
            for (int c = 0; c < chunkCount; c++) {
                int offset = c * rowsPerChunk;
                assertThat(got.get(offset)).isEqualTo(dict[c % dict.length]);
                assertThat(got.get(offset + 1)).isEqualTo(dict[(c + 1) % dict.length]);
            }
        }
    }

    @Test
    void lowCardinality_nullableUtf8_acrossChunks_usesGlobalDict(@TempDir Path tmp) throws IOException {
        // Given — a NULLABLE low-cardinality column (8 categories) with ~10% nulls scattered per
        // chunk. The fix admits nullable columns to the global dict: one shared dictionary, per-chunk
        // masked codes. This guards both size (one dict, not N per-chunk dicts) and that null
        // positions round-trip exactly.
        var schema = new DType.Struct(List.of(ColumnName.of("status")),
                List.of(DType.UTF8.withNullable(true)), false);
        Path file = tmp.resolve("nullable_status.vortex");
        String[] dict = {"a", "b", "c", "d", "e", "f", "g", "h"};
        int rowsPerChunk = 1_000;
        int chunkCount = 5;

        String[] expected = new String[rowsPerChunk * chunkCount];
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, schema, WriteOptions.cascading(3))) {
            // When — every 10th row is null
            for (int c = 0; c < chunkCount; c++) {
                String[] data = new String[rowsPerChunk];
                for (int i = 0; i < rowsPerChunk; i++) {
                    String value = (c + i) % 10 == 0 ? null : dict[(c + i) % dict.length];
                    data[i] = value;
                    expected[c * rowsPerChunk + i] = value;
                }
                sut.writeChunk(Map.of(ColumnName.of("status"), data));
            }
        }

        // Then — file stays small: one shared dict (8 strings) + N chunks of U8 codes + masks.
        // 5 chunks × 1000 rows × (1 code byte + validity bit) ≈ 5.6 KB; far below N per-chunk dicts.
        long size = Files.size(file);
        assertThat(size).as("global dict for 5 nullable chunks of 1000 strings").isLessThan(12_000L);

        // And both values AND null positions round-trip exactly across all chunks.
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            List<String> got = readNullableStrings(vf, "status");
            assertThat(got).containsExactly(expected);
        }
    }

    @Test
    void highCardinality_utf8_fallsBackToPerChunk(@TempDir Path tmp) throws IOException {
        // Given — every row is unique, so global dict ratio gate fails (50% rule).
        Path file = tmp.resolve("ids.vortex");
        int rows = 2_000;
        String[] data = new String[rows];
        for (int i = 0; i < rows; i++) {
            data[i] = "id-" + i;
        }

        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.cascading(3))) {
            // When
            sut.writeChunk(Map.of(ColumnName.of("status"), data));
        }

        // Then — file is readable, all rows round-trip (correctness, not size).
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            List<String> got = readAllStrings(vf, "status");
            assertThat(got).hasSize(rows);
            for (int i = 0; i < rows; i++) {
                assertThat(got.get(i)).isEqualTo(data[i]);
            }
        }
    }

    @Test
    void mediumCardinality_utf8_usesU16Codes(@TempDir Path tmp) throws IOException {
        // Given — 300 distinct strings over 1000 rows: a global-dict candidate whose dict size (300)
        // is above the 256 U8-code boundary, so codes are U16. Exercises emitCodes's U16
        // arm (the existing low-card test has 3 values → U8).
        Path file = tmp.resolve("u16_utf8.vortex");
        int rows = 1_000;
        int cardinality = 300;
        String[] data = new String[rows];
        for (int i = 0; i < rows; i++) {
            data[i] = "s" + (i % cardinality);
        }

        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.cascading(3))) {
            sut.writeChunk(Map.of(ColumnName.of("status"), data));
        }

        // Then — every value round-trips through the U16-coded dict
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            assertThat(readAllStrings(vf, "status")).containsExactly(data);
        }
    }

    @Test
    void cardinalityExceededAcrossChunks_utf8_fallsBackToPerChunk(@TempDir Path tmp) throws IOException {
        // Given — the first chunk is a candidate (400 distinct / 1000 rows, < 50% unique), buffering
        // the column for a global dict; a second chunk of fresh uniques pushes the total cardinality
        // past GLOBAL_DICT_MAX_CARDINALITY (2048), tripping the per-chunk fallback in
        // writeGlobalDictUtf8Column once the full key set is known at flush.
        String[] c0 = new String[1_000];
        for (int i = 0; i < c0.length; i++) {
            c0[i] = "v" + (i % 400);
        }
        String[] c1 = new String[1_700];
        for (int i = 0; i < c1.length; i++) {
            c1[i] = "u" + i;
        }
        Path file = tmp.resolve("card_exceeded_utf8.vortex");

        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.cascading(3))) {
            // When — 400 + 1700 = 2100 distinct > 2048
            sut.writeChunk(Map.of(ColumnName.of("status"), c0));
            sut.writeChunk(Map.of(ColumnName.of("status"), c1));
        }

        // Then — the fallback round-trips both chunks in order
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            List<String> got = readAllStrings(vf, "status");
            assertThat(got).hasSize(2_700);
            assertThat(got.subList(0, 1_000)).containsExactly(c0);
            assertThat(got.subList(1_000, 2_700)).containsExactly(c1);
        }
    }

    @Test
    void utf8_globalDict_disabled_byOptions(@TempDir Path tmp) throws IOException {
        // Given — globalDict() off, low-cardinality column → falls back to per-chunk DictEncoding.
        // Both paths round-trip correctly; this test guards the opt-out.
        Path file = tmp.resolve("status_nogdict.vortex");
        String[] data = new String[500];
        for (int i = 0; i < data.length; i++) {
            data[i] = i % 2 == 0 ? "open" : "closed";
        }

        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.cascading(3).withGlobalDict(false))) {
            // When
            sut.writeChunk(Map.of(ColumnName.of("status"), data));
        }

        // Then
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            List<String> got = readAllStrings(vf, "status");
            assertThat(got).containsExactly(data);
        }
    }

    @Test
    void retainedBytesBudgetExceeded_utf8_demotesToPerChunkChunkedLayout(@TempDir Path tmp) throws IOException {
        // Given — a column that looks low-cardinality on its FIRST chunk (3 distinct values, well
        // under the 50% ratio + 2048 cardinality gates) so it is admitted to the global dict and its
        // per-chunk codes start being buffered. Cardinality never grows past 3, so the cardinality cap
        // never fires — only the secondary aggregate code-array byte budget (ADR 0021) catches this.
        // We lower the budget via WriteOptions so it is crossed partway through the file, exercising
        // the byte-budget demotion path independently of the cardinality path. Buffering is now
        // cardinality-bounded, so the budget guards ~2 B/row of codes (not raw strings) — a far
        // smaller quantity, hence a far smaller budget here than the old raw-byte version.
        Path file = tmp.resolve("retained_budget_utf8.vortex");
        String[] dict = {"open", "closed", "delivered"};
        int rowsPerChunk = 2_000;
        int chunkCount = 6;
        String[] expected = new String[rowsPerChunk * chunkCount];

        // Each chunk buffers 2000 rows × 2 B/row = 4000 B of codes plus a ~120 B dedup map. A budget
        // of 8000 B is crossed after the 3rd chunk (~12 KB retained), forcing demotion partway through
        // the file. Configured via the real public WriteOptions surface rather than a test-only seam.
        WriteOptions opts = WriteOptions.cascading(3).withGlobalDictMaxRetainedBytes(8_000L);
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, opts)) {
            // When
            for (int c = 0; c < chunkCount; c++) {
                String[] data = new String[rowsPerChunk];
                for (int i = 0; i < rowsPerChunk; i++) {
                    String value = dict[(c + i) % dict.length];
                    data[i] = value;
                    expected[c * rowsPerChunk + i] = value;
                }
                sut.writeChunk(Map.of(ColumnName.of("status"), data));
            }
        }

        // Then — the demoted column is a plain Chunked-of-Flats layout with one Flat per chunk (the
        // buffered-then-flushed chunks plus the per-chunk-encoded remainder), NOT a single Dict
        // layout. A Dict here would mean the whole column was still buffered in memory — the OOM.
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            var columnLayout = unwrapZoned(vf.layout().children().getFirst());
            assertThat(columnLayout.isDict()).as("demoted column must not be a global dict").isFalse();
            assertThat(columnLayout.isChunked()).as("demoted column is a chunked layout").isTrue();
            assertThat(columnLayout.children())
                    .as("one Flat per written chunk after demotion")
                    .hasSize(chunkCount)
                    .allSatisfy(child -> assertThat(child.isFlat()).isTrue());

            // And every value round-trips exactly across all chunks despite the mid-file demotion.
            List<String> got = readAllStrings(vf, "status");
            assertThat(got).containsExactly(expected);
        }
    }

    @Test
    void retainedBytesBudgetExceeded_nullableUtf8_demotesAndRestoresNulls(@TempDir Path tmp) throws IOException {
        // Given — the nullable twin of retainedBytesBudgetExceeded_utf8_..., exercising the
        // reconstructChunk NULLABLE branch (Utf8 arm) that ADR 0021's "Risks to manage" flags. The
        // column looks low-cardinality on its first chunk (3 distinct values) WITH ~10% nulls
        // scattered per chunk, so it is admitted and its per-chunk codes+validity are buffered.
        // Cardinality stays at 3, so only the aggregate code-array byte budget catches it: a lowered
        // budget is crossed partway through the file, forcing demotion. Reconstruction must restore
        // null placeholders at invalid positions and re-wrap each buffered chunk in NullableData —
        // asserted end-to-end via the OUTPUT SHAPE (Chunked-of-Flats, not a shared Dict) plus an
        // exact value-and-null round-trip.
        var schema = new DType.Struct(List.of(ColumnName.of("status")),
                List.of(DType.UTF8.withNullable(true)), false);
        Path file = tmp.resolve("retained_budget_nullable_utf8.vortex");
        String[] dict = {"open", "closed", "delivered"};
        int rowsPerChunk = 2_000;
        int chunkCount = 6;
        String[] expected = new String[rowsPerChunk * chunkCount];

        WriteOptions opts = WriteOptions.cascading(3).withGlobalDictMaxRetainedBytes(8_000L);
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, schema, opts)) {
            // When — every 10th row is null so the buffered chunks carry a validity bitmap; the budget
            // is crossed partway through, demoting the buffered-then-flushed chunks to per-chunk Flats.
            for (int c = 0; c < chunkCount; c++) {
                String[] data = new String[rowsPerChunk];
                for (int i = 0; i < rowsPerChunk; i++) {
                    String value = (c + i) % 10 == 0 ? null : dict[(c + i) % dict.length];
                    data[i] = value;
                    expected[c * rowsPerChunk + i] = value;
                }
                sut.writeChunk(Map.of(ColumnName.of("status"), data));
            }
        }

        // Then — the demoted column is Chunked-of-Flats (one Flat per chunk), never a shared Dict.
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            var columnLayout = unwrapZoned(vf.layout().children().getFirst());
            assertThat(columnLayout.isDict()).as("demoted column must not be a global dict").isFalse();
            assertThat(columnLayout.isChunked()).as("demoted column is a chunked layout").isTrue();
            assertThat(columnLayout.children())
                    .as("one Flat per written chunk after demotion")
                    .hasSize(chunkCount)
                    .allSatisfy(child -> assertThat(child.isFlat()).isTrue());

            // And every value AND every null round-trips exactly, confirming the nullable
            // reconstruction path (validity restore + NullableData re-wrap) end-to-end.
            List<String> got = readNullableStrings(vf, "status");
            assertThat(got).containsExactly(expected);
        }
    }

    /// Unwraps a column's [io.github.dfa1.vortex.reader.layout.Layout] Zoned/Stats wrapper (the
    /// writer wraps every column in one for zone-map pruning) to reach the encoding layout beneath.
    private static io.github.dfa1.vortex.reader.layout.Layout unwrapZoned(
            io.github.dfa1.vortex.reader.layout.Layout layout) {
        return layout.isZoned() ? layout.children().getFirst() : layout;
    }

    /// Reads a nullable Utf8 column, mapping invalid rows to `null` so null positions are asserted
    /// alongside values. A nullable dict column decodes to a [MaskedArray] over the Utf8 payload.
    private static List<String> readNullableStrings(VortexReader vf, String col) {
        var collected = new ArrayList<String>();
        try (var iter = vf.scan(ScanOptions.all())) {
            iter.forEachRemaining(c -> {
                Array arr = c.column(col);
                MaskedArray masked = arr instanceof MaskedArray m ? m : null;
                VarBinArray vb = (VarBinArray) (masked != null ? masked.inner() : arr);
                for (long i = 0; i < vb.length(); i++) {
                    collected.add(masked != null && !masked.isValid(i) ? null : vb.getString(i));
                }
            });
        }
        return collected;
    }
}
