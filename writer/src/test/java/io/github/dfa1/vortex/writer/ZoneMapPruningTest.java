package io.github.dfa1.vortex.writer;

import io.github.dfa1.vortex.core.model.DType;
import io.github.dfa1.vortex.reader.Chunk;
import io.github.dfa1.vortex.reader.ReadRegistry;
import io.github.dfa1.vortex.reader.RowFilter;
import io.github.dfa1.vortex.reader.ScanOptions;
import io.github.dfa1.vortex.reader.VortexReader;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.decode.PrimitiveEncodingDecoder;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/// Round-trip tests verifying zone-map chunk pruning via RowFilter.
class ZoneMapPruningTest {

    private static final DType.Struct SCHEMA = new DType.Struct(
            List.of("id"),
            List.of(DType.I64),
            false);

    // Three chunks: id in [1..50], [51..100], [101..150]
    private static Path writeThreeChunks(Path tmp) throws IOException {
        Path file = tmp.resolve("three_chunks.vtx");
        try (var ch = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
             var sut = VortexWriter.create(ch, SCHEMA, WriteOptions.defaults())) {
            sut.writeChunk(Map.of("id", range(1L, 50L)));
            sut.writeChunk(Map.of("id", range(51L, 100L)));
            sut.writeChunk(Map.of("id", range(101L, 150L)));
        }
        return file;
    }

    /// Returns one entry per surviving chunk: its row count after filter pruning.
    private static List<Long> scanRowCounts(Path file, RowFilter filter) throws IOException {
        var opts = new ScanOptions(List.of(), filter, ScanOptions.NO_LIMIT);
        var registry = primitiveRegistry();
        var rowCounts = new ArrayList<Long>();
        try (var vf = VortexReader.open(file, registry);
             var iter = vf.scan(opts)) {
            iter.forEachRemaining(c -> rowCounts.add(c.rowCount()));
        }
        return rowCounts;
    }

    private static long[] range(long from, long to) {
        long[] arr = new long[(int) (to - from + 1)];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = from + i;
        }
        return arr;
    }

    private static ReadRegistry primitiveRegistry() {
        return ReadRegistry.builder().register(new PrimitiveEncodingDecoder()).build();
    }

    @Test
    void gte_prunesChunksBelowThreshold(@TempDir Path tmp) throws IOException {
        // Given — chunk 1 max=50, threshold=75 → chunk 1 pruned
        Path file = writeThreeChunks(tmp);

        // When
        List<Long> rowCounts = scanRowCounts(file, RowFilter.gte("id", 75L));

        // Then
        assertThat(rowCounts).containsExactly(50L, 50L); // chunk 2, 3
    }

    @Test
    void lte_prunesChunksAboveThreshold(@TempDir Path tmp) throws IOException {
        // Given — chunk 3 min=101, threshold=75 → chunk 3 pruned
        Path file = writeThreeChunks(tmp);

        // When
        List<Long> rowCounts = scanRowCounts(file, RowFilter.lte("id", 75L));

        // Then
        assertThat(rowCounts).containsExactly(50L, 50L); // chunk 1, 2
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    @Test
    void eq_prunesChunksExcludingValue(@TempDir Path tmp) throws IOException {
        // Given — id=75 only falls in chunk 2 [51..100]; chunks 1 and 3 pruned
        Path file = writeThreeChunks(tmp);

        // When
        List<Long> rowCounts = scanRowCounts(file, RowFilter.eq("id", 75L));

        // Then
        assertThat(rowCounts).containsExactly(50L); // chunk 2
    }

    @Test
    void and_prunesChunksExcludedByAnySubFilter(@TempDir Path tmp) throws IOException {
        // Given — AND(id>=51, id<=100): chunk 1 pruned by gte, chunk 3 pruned by lte
        Path file = writeThreeChunks(tmp);

        // When
        List<Long> rowCounts = scanRowCounts(file, RowFilter.and(
                RowFilter.gte("id", 51L),
                RowFilter.lte("id", 100L)));

        // Then
        assertThat(rowCounts).containsExactly(50L); // chunk 2
    }

    @Test
    void noFilter_returnsAllChunks(@TempDir Path tmp) throws IOException {
        // Given
        Path file = writeThreeChunks(tmp);

        // When
        List<Long> rowCounts = scanRowCounts(file, null);

        // Then
        assertThat(rowCounts).hasSize(3);
    }

    /// Row-level filtering: within surviving chunks, only rows satisfying the predicate are collected.
    /// Zone-map pruning reduces the chunk set; row-level loops must still check each element.
    @Nested
    class RowLevel {

        @Test
        void eq_onlyExactMatchCollected(@TempDir Path tmp) throws IOException {
            // Given — id=75 lands in chunk 2 [51..100]; exactly one row matches
            Path file = writeThreeChunks(tmp);
            long predicate = 75L;
            var opts = ScanOptions.columns("id").withFilter(RowFilter.eq("id", predicate));

            // When
            List<Long> matched = collectMatching(file, opts, v -> v == predicate);

            // Then
            assertThat(matched).hasSize(1).allSatisfy(v -> assertThat(v).isEqualTo(predicate));
        }

        @Test
        void gte_allCollectedValuesAtOrAboveThreshold(@TempDir Path tmp) throws IOException {
            // Given — threshold=75: chunks 2 and 3 survive zone-map; rows below 75 in chunk 2 must not appear
            Path file = writeThreeChunks(tmp);
            long threshold = 75L;
            var opts = ScanOptions.columns("id").withFilter(RowFilter.gte("id", threshold));

            // When
            List<Long> matched = collectMatching(file, opts, v -> v >= threshold);

            // Then — 76 matching rows: 75..100 (26) + 101..150 (50)
            assertThat(matched).hasSize(76).allSatisfy(v -> assertThat(v).isGreaterThanOrEqualTo(threshold));
        }

        @Test
        void and_allCollectedValuesInsideRange(@TempDir Path tmp) throws IOException {
            // Given — AND(id>=60, id<=90): only chunk 2 survives zone-map; 31 rows match
            Path file = writeThreeChunks(tmp);
            long lo = 60L, hi = 90L;
            var opts = ScanOptions.columns("id").withFilter(
                    RowFilter.and(RowFilter.gte("id", lo), RowFilter.lte("id", hi)));

            // When
            List<Long> matched = collectMatching(file, opts, v -> v >= lo && v <= hi);

            // Then
            assertThat(matched).hasSize(31)
                    .allSatisfy(v -> assertThat(v).isBetween(lo, hi));
        }

        private List<Long> collectMatching(Path file, ScanOptions opts, java.util.function.LongPredicate predicate)
                throws IOException {
            var result = new ArrayList<Long>();
            try (VortexReader vf = VortexReader.open(file, primitiveRegistry());
                 var iter = vf.scan(opts)) {
                while (iter.hasNext()) {
                    try (Chunk c = iter.next()) {
                        LongArray col = c.column("id");
                        for (long i = 0; i < col.length(); i++) {
                            long v = col.getLong(i);
                            if (predicate.test(v)) {
                                result.add(v);
                            }
                        }
                    }
                }
            }
            return result;
        }
    }
}
