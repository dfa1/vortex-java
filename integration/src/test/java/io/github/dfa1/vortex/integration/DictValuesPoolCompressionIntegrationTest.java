package io.github.dfa1.vortex.integration;

import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.inspect.InspectorTree;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.VarBinArray;
import io.github.dfa1.vortex.writer.VortexWriter;
import io.github.dfa1.vortex.writer.WriteOptions;
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

/// Global-dict Utf8 columns compress their distinct-values pool through the normal Utf8
/// competition (FSST/VarBin/Zstd) instead of hardcoded raw varbin (#299). Rust dict-encodes
/// *and* FSST-compresses the same columns; before this change Java left the pool uncompressed,
/// which was a large part of the nyc-311 file-size gap.
class DictValuesPoolCompressionIntegrationTest {

    private static final ColumnName COL = ColumnName.of("desc");
    private static final DType.Struct SCHEMA = new DType.Struct(List.of(COL), List.of(DType.UTF8), false);

    /// Builds a low-cardinality column whose distinct values share a long common prefix, so FSST
    /// beats raw varbin on the values pool decisively. `distinct * 2 < rows` keeps it inside the
    /// global-dict gate; `distinct < 2048` keeps it inside the current cardinality cap so this test
    /// exercises Finding 2 alone (independent of the cap raise).
    private static String[] redundantChunk(int rows, int distinct, int chunkSeed) {
        String prefix = "New York City Department of Housing Preservation and Development complaint at ";
        String[] out = new String[rows];
        for (int i = 0; i < rows; i++) {
            int id = (chunkSeed + i) % distinct;
            out[i] = prefix + "site #" + id + " STREET";
        }
        return out;
    }

    @Test
    void globalDictUtf8_valuesPool_isFsstCompressed_andRoundTrips(@TempDir Path tmp) throws IOException {
        // Given — 800 distinct long strings sharing a 78-char prefix, repeated across 5 chunks.
        Path file = tmp.resolve("dict_fsst.vortex");
        int rowsPerChunk = 4_000;
        int chunkCount = 5;
        int distinct = 800;

        List<String> expected = new ArrayList<>();
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.cascading(3))) {
            // When
            for (int c = 0; c < chunkCount; c++) {
                String[] data = redundantChunk(rowsPerChunk, distinct, c * 37);
                expected.addAll(List.of(data));
                sut.writeChunk(Map.of(COL, data));
            }
        }

        // Then — the values pool is FSST-compressed. The global dictionary itself is a *layout*
        // (LAYOUT_DICT), so it is not listed among the segment encodings; FSST winning the
        // values-pool competition is the observable proof the pool is no longer raw varbin.
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            InspectorTree tree = InspectorTree.build(vf);
            assertThat(tree.usedEncodings())
                    .as("FSST compresses the dictionary values pool")
                    .contains("vortex.fsst");
        }

        // And global-dict dedup + FSST keeps the file far below the ~1.8 MB of raw repeated
        // strings (20 000 rows × ~90 B) — dedup to 800 distinct, values pool FSST-compressed.
        assertThat(Files.size(file))
                .as("dict dedup + FSST values pool")
                .isLessThan(300_000L);

        // And every value round-trips exactly.
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            List<String> got = readAllStrings(vf);
            assertThat(got).isEqualTo(expected);
        }
    }

    @Test
    void utf8_cardinalityAbovePriorCap_admittedToGlobalDict(@TempDir Path tmp) throws IOException {
        // Given — 8 000 distinct values: above the old 2048 numeric-era cap (so previously demoted
        // to per-chunk), below the 32768 Utf8 cap, with >50% dedup so the ratio gate admits it.
        Path file = tmp.resolve("high_card.vortex");
        int rowsPerChunk = 20_000;
        int chunkCount = 4;
        int distinct = 8_000;

        List<String> expected = new ArrayList<>();
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.cascading(3))) {
            // When
            for (int c = 0; c < chunkCount; c++) {
                String[] data = redundantChunk(rowsPerChunk, distinct, c * 101);
                expected.addAll(List.of(data));
                sut.writeChunk(Map.of(COL, data));
            }
        }

        // Then — the column is stored as one global dictionary (a DICT layout node), not a per-chunk
        // fallback. Before the type-aware cap a 8 000-distinct column produced no DICT layout.
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            assertThat(hasDictLayout(InspectorTree.build(vf).root()))
                    .as("8 000-distinct Utf8 column admitted to the global dictionary")
                    .isTrue();
        }

        // And every value round-trips exactly.
        try (var vf = VortexReader.open(file, ReadRegistry.loadAll())) {
            assertThat(readAllStrings(vf)).isEqualTo(expected);
        }
    }

    private static boolean hasDictLayout(InspectorTree.Node node) {
        if (node.layout().isDict()) {
            return true;
        }
        for (InspectorTree.Node child : node.children()) {
            if (hasDictLayout(child)) {
                return true;
            }
        }
        return false;
    }

    private static List<String> readAllStrings(VortexReader vf) {
        List<String> out = new ArrayList<>();
        try (var iter = vf.scan(ScanOptions.columns("desc"))) {
            while (iter.hasNext()) {
                try (var chunk = iter.next()) {
                    Array col = chunk.column("desc");
                    VarBinArray strings = (VarBinArray) col;
                    long n = strings.length();
                    for (long i = 0; i < n; i++) {
                        out.add(strings.getString(i));
                    }
                }
            }
        }
        return out;
    }
}
