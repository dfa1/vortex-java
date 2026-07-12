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
        // is above the 256 U8-code boundary, so codes are U16. Exercises buildUtf8CodesArray's U16
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
