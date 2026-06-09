package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.array.Array;
import io.github.dfa1.vortex.core.array.VarBinArray;
import io.github.dfa1.vortex.encoding.Registry;
import io.github.dfa1.vortex.io.VortexReader;
import io.github.dfa1.vortex.scan.ScanOptions;
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

import static org.assertj.core.api.Assertions.assertThat;

/// Global dictionary encoding for low-cardinality Utf8 columns: one dict shared
/// across all chunks instead of per-chunk dictionaries.
class GlobalDictUtf8Test {

    private static final DType.Struct SCHEMA = new DType.Struct(
            List.of("status"),
            List.of(new DType.Utf8(false)),
            false);

    private static List<String> readAllStrings(VortexReader vf, String col) {
        var collected = new ArrayList<String>();
        try (var iter = vf.scan(ScanOptions.all())) {
            iter.forEachRemaining(c -> {
                Array arr = c.column(col);
                VarBinArray vb = (VarBinArray) arr;
                for (long i = 0; i < vb.length(); i++) {
                    collected.add(vb.getString(i));
                }
            });
        }
        return collected;
    }

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
                sut.writeChunk(Map.of("status", data));
            }
        }

        // Then — file is tiny (one shared dict of 3 strings ~30 bytes + N codes of U8).
        // 5 chunks × 1000 rows × U8 codes ≈ 5 KB + 30-byte dict + overhead.
        long size = Files.size(file);
        assertThat(size).as("global dict for 5 chunks of 1000 strings").isLessThan(8_000L);

        // And values round-trip exactly across all chunks.
        try (var vf = VortexReader.open(file, Registry.loadAll())) {
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
            sut.writeChunk(Map.of("status", data));
        }

        // Then — file is readable, all rows round-trip (correctness, not size).
        try (var vf = VortexReader.open(file, Registry.loadAll())) {
            List<String> got = readAllStrings(vf, "status");
            assertThat(got).hasSize(rows);
            for (int i = 0; i < rows; i++) {
                assertThat(got.get(i)).isEqualTo(data[i]);
            }
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
            sut.writeChunk(Map.of("status", data));
        }

        // Then
        try (var vf = VortexReader.open(file, Registry.loadAll())) {
            List<String> got = readAllStrings(vf, "status");
            assertThat(got).containsExactly(data);
        }
    }
}
