package io.github.dfa1.vortex.reader;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.ColumnName;
import io.github.dfa1.vortex.core.model.LayoutId;
import io.github.dfa1.vortex.reader.ScanIterator.ChunkSpec;
import io.github.dfa1.vortex.reader.layout.Layout;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Unit tests for [ScanIterator#buildChunks(Map)] — the scan planner that merges each column's
/// chunk grid into one split grid (the Rust reference's `StructReader::register_splits` union of
/// field boundaries). The scaled-down grids below mirror the two real corpus files from #221:
/// `emotions-dataset-for-nlp` (nested boundaries) and `uci-beijing-multi-site-air-quality`
/// (disjoint boundaries).
class ScanIteratorChunkGridTest {

    private static final ColumnName A = ColumnName.of("a");
    private static final ColumnName B = ColumnName.of("b");

    @Test
    void alignedGridsDecodeEveryColumnAsAWholeChunk() {
        // Given two columns with the *same* grid [4, 4] over 8 rows — the aligned N-vs-N fast path.
        var columnFlats = flats(A, new long[]{4, 4}, B, new long[]{4, 4});

        // When
        List<ChunkSpec> result = ScanIterator.buildChunks(columnFlats);

        // Then the merged grid equals the shared grid: two windows, and every column's window is a
        // whole chunk (offset 0, window rows == covering-flat rows) so decode stays slice-free.
        assertThat(rowCounts(result)).containsExactly(4L, 4L);
        assertWholeChunk(result.get(0), A, 4);
        assertWholeChunk(result.get(0), B, 4);
        assertWholeChunk(result.get(1), A, 4);
        assertWholeChunk(result.get(1), B, 4);
    }

    @Test
    void singleFlatColumnSharesTheChunkedGrid() {
        // Given one full-column flat [8] beside a chunked column [4, 4] — the 1-vs-N case.
        var columnFlats = flats(A, new long[]{8}, B, new long[]{4, 4});

        // When
        List<ChunkSpec> result = ScanIterator.buildChunks(columnFlats);

        // Then the single flat is the covering chunk of both windows, sliced at 0 then 4, while the
        // chunked column contributes a whole chunk per window.
        assertThat(rowCounts(result)).containsExactly(4L, 4L);
        assertSlice(result.get(0), A, 8, 0);
        assertWholeChunk(result.get(0), B, 4);
        assertSlice(result.get(1), A, 8, 4);
        assertWholeChunk(result.get(1), B, 4);
    }

    @Test
    void nestedBoundariesSliceTheCoarseColumnAtTheFineGrid() {
        // Given the emotions-dataset-for-nlp shape scaled down: a coarse column [8, 8, 8, 4] (like
        // `label`'s [131072 ×3, 23593]) beside a fine column of 2-row chunks (like `text`'s 16384
        // grid, where 8 = 4 × 2 so every coarse boundary is also a fine boundary).
        var columnFlats = flats(
                A, new long[]{8, 8, 8, 4},
                B, new long[]{2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2, 2});

        // When
        List<ChunkSpec> result = ScanIterator.buildChunks(columnFlats);

        // Then the merged grid is the fine grid (it already contains every coarse boundary): 14
        // windows of 2. The fine column is a whole chunk each window; the coarse column is sliced
        // within whichever coarse chunk covers the window, so it is decoded once per coarse chunk.
        assertThat(result).hasSize(14);
        assertThat(rowCounts(result)).allMatch(n -> n == 2L);
        // Rows [0, 8) fall inside coarse chunk 0 at offsets 0, 2, 4, 6.
        assertSlice(result.get(0), A, 8, 0);
        assertSlice(result.get(3), A, 8, 6);
        // Rows [8, 16) fall inside coarse chunk 1 (rowCount 8) at offsets 0, 2, 4, 6.
        assertSlice(result.get(4), A, 8, 0);
        assertSlice(result.get(7), A, 8, 6);
        // Rows [24, 28) fall inside coarse chunk 3 (rowCount 4) at offsets 0, 2.
        assertSlice(result.get(12), A, 4, 0);
        assertSlice(result.get(13), A, 4, 2);
        // The fine column is always a whole 2-row chunk.
        for (ChunkSpec spec : result) {
            assertWholeChunk(spec, B, 2);
        }
    }

    @Test
    void disjointBoundariesSpliceAtTheMergedGrid() {
        // Given the uci-beijing-multi-site-air-quality shape scaled down: [3, 3, 2] vs [4, 4] over 8
        // rows. The boundaries do not nest (3 + 3 = 6 != 4, mirroring 40960 + 49152 != 131072), so
        // neither grid refines the other — the scan must emit windows at the union of boundaries.
        var columnFlats = flats(
                A, new long[]{3, 3, 2},
                B, new long[]{4, 4});

        // When
        List<ChunkSpec> result = ScanIterator.buildChunks(columnFlats);

        // Then the merged grid is {0, 3, 4, 6, 8} -> windows [0,3) [3,4) [4,6) [6,8), and every
        // column slices its covering chunk to each window (a chunk spanning several windows is
        // named identically across them, so it is decoded once and sliced).
        assertThat(rowCounts(result)).containsExactly(3L, 1L, 2L, 2L);
        // Column A [3, 3, 2]: chunk0 covers [0,3) whole; chunk1 covers [3,4) and [4,6); chunk2 whole.
        assertWholeChunk(result.get(0), A, 3);
        assertSlice(result.get(1), A, 3, 0);
        assertSlice(result.get(2), A, 3, 1);
        assertThat(coveringFlat(result.get(1), A)).isSameAs(coveringFlat(result.get(2), A));
        assertWholeChunk(result.get(3), A, 2);
        // Column B [4, 4]: chunk0 covers [0,3) and [3,4); chunk1 covers [4,6) and [6,8).
        assertSlice(result.get(0), B, 4, 0);
        assertSlice(result.get(1), B, 4, 3);
        assertThat(coveringFlat(result.get(0), B)).isSameAs(coveringFlat(result.get(1), B));
        assertSlice(result.get(2), B, 4, 0);
        assertSlice(result.get(3), B, 4, 2);
        assertThat(coveringFlat(result.get(2), B)).isSameAs(coveringFlat(result.get(3), B));
    }

    @Test
    void columnShorterThanMergedGridThrows() {
        // Given two columns whose totals disagree — A spans 8 rows [4, 4] but B only 6 [3, 3]. A
        // well-formed file keeps every column the same length; a malformed/untrusted layout may not.
        // The merged grid {0, 3, 4, 6, 8} then produces a window [6, 8) that lies beyond B's last
        // chunk, so the covering-chunk advance loop runs B's cursor off the end. The guard must
        // surface that as a VortexException rather than an ArrayIndexOutOfBoundsException.
        var columnFlats = flats(A, new long[]{4, 4}, B, new long[]{3, 3});

        // When / Then
        assertThatThrownBy(() -> ScanIterator.buildChunks(columnFlats))
                .isInstanceOf(VortexException.class)
                .hasMessageContaining("no chunk covering");
    }

    @Test
    void emptyProjectionYieldsNoChunks() {
        // Given no columns
        var columnFlats = new LinkedHashMap<ColumnName, List<Layout>>();

        // When
        List<ChunkSpec> result = ScanIterator.buildChunks(columnFlats);

        // Then
        assertThat(result).isEmpty();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /// Builds an insertion-ordered `column -> flats` map for two columns. A [LinkedHashMap] states
    /// the intended column order explicitly (the planner's output is order-independent and read
    /// back by name here, but an unordered [Map#of] would hide that intent).
    private static Map<ColumnName, List<Layout>> flats(ColumnName n1, long[] g1, ColumnName n2, long[] g2) {
        var out = new LinkedHashMap<ColumnName, List<Layout>>();
        out.put(n1, toFlats(g1));
        out.put(n2, toFlats(g2));
        return out;
    }

    private static List<Layout> toFlats(long[] chunkRows) {
        var list = new ArrayList<Layout>(chunkRows.length);
        for (long rows : chunkRows) {
            list.add(new Layout(LayoutId.FLAT, rows, null, List.of(), List.of()));
        }
        return list;
    }

    private static List<Long> rowCounts(List<ChunkSpec> chunks) {
        var out = new ArrayList<Long>(chunks.size());
        for (ChunkSpec spec : chunks) {
            out.add(spec.rowCount());
        }
        return out;
    }

    private static Layout coveringFlat(ChunkSpec spec, ColumnName column) {
        return spec.layoutFor(column);
    }

    private static long sliceOffset(ChunkSpec spec, ColumnName column) {
        ColumnName[] names = spec.columnNames();
        for (int i = 0; i < names.length; i++) {
            if (names[i].equals(column)) {
                return spec.sliceOffsets()[i];
            }
        }
        throw new IllegalStateException("no such column " + column);
    }

    /// Asserts the window decodes `column` as a whole covering chunk (fast path: offset 0 and the
    /// window's rows exactly equal the covering flat's rows, so no slice wrapper is created).
    private static void assertWholeChunk(ChunkSpec spec, ColumnName column, long flatRows) {
        assertThat(coveringFlat(spec, column).rowCount()).isEqualTo(flatRows);
        assertThat(sliceOffset(spec, column)).isZero();
        assertThat(spec.rowCount()).isEqualTo(flatRows);
    }

    /// Asserts the window slices `column`'s covering chunk (of `flatRows` rows) at `offset`.
    private static void assertSlice(ChunkSpec spec, ColumnName column, long flatRows, long offset) {
        assertThat(coveringFlat(spec, column).rowCount()).isEqualTo(flatRows);
        assertThat(sliceOffset(spec, column)).isEqualTo(offset);
    }
}
