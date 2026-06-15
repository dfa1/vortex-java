package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/// Unit tests for the lazy FastLanes RLE records. Cover scalar lookup, constant-run
/// fast path, multi-chunk forEach iteration, fold reduction, and offset slicing.
///
/// Note: FastLanes RLE works in 1024-row chunks. Tests construct the records directly
/// so they exercise the lazy semantics without needing a full encoder round-trip.
class LazyRleArrayTest {

    private static final DType I64 = new DType.Primitive(PType.I64, false);
    private static final DType I32 = new DType.Primitive(PType.I32, false);

    @Nested
    class LongDispatch {

        @Test
        void singleChunkAllSameValue_constantRunFastPath() {
            // Given a single 1024-row chunk with 1 distinct value (42).
            // valuesIdxOffsets[0] = 0, indices irrelevant (constant fast path).
            long[] values = {42L};
            int[] indices = new int[1024];
            long[] valuesIdxOffsets = {0L};
            var sut = new LazyRleLongArray(I64, 1024, values, indices,
                    valuesIdxOffsets, 0L, 1L, 1, 0);

            assertThat(sut.getLong(0)).isEqualTo(42L);
            assertThat(sut.getLong(500)).isEqualTo(42L);
            assertThat(sut.getLong(1023)).isEqualTo(42L);
        }

        @Test
        void singleChunkWithIndices_perRowLookup() {
            // Given one chunk with values [10, 20, 30], indices selecting them in
            // pattern 0,1,2,0,1,2,... over the first 6 rows.
            long[] values = {10L, 20L, 30L};
            int[] indices = new int[1024];
            for (int i = 0; i < 6; i++) {
                indices[i] = i % 3;
            }
            long[] valuesIdxOffsets = {0L};
            var sut = new LazyRleLongArray(I64, 6, values, indices,
                    valuesIdxOffsets, 0L, 3L, 1, 0);

            var seen = new ArrayList<java.lang.Long>();
            sut.forEachLong(seen::add);

            assertThat(seen).containsExactly(10L, 20L, 30L, 10L, 20L, 30L);
        }

        @Test
        void multiChunkBoundary_walksAcrossChunks() {
            // Given two chunks: chunk 0 = constant 1, chunk 1 = constant 2.
            long[] values = {1L, 2L};
            int[] indices = new int[2 * 1024];  // unused for constant runs
            long[] valuesIdxOffsets = {0L, 1L};
            // length = 1026 covers all of chunk 0 + first 2 rows of chunk 1
            var sut = new LazyRleLongArray(I64, 1026, values, indices,
                    valuesIdxOffsets, 0L, 2L, 2, 0);

            assertThat(sut.getLong(0)).isEqualTo(1L);
            assertThat(sut.getLong(1023)).isEqualTo(1L);
            assertThat(sut.getLong(1024)).isEqualTo(2L);
            assertThat(sut.getLong(1025)).isEqualTo(2L);
        }

        @Test
        void offsetSkipsLeadingRows() {
            // Given chunk 0 = constant 5; offset=100 means logical row 0 -> absolute 100.
            long[] values = {5L};
            int[] indices = new int[1024];
            long[] valuesIdxOffsets = {0L};
            var sut = new LazyRleLongArray(I64, 10, values, indices,
                    valuesIdxOffsets, 0L, 1L, 1, 100);

            assertThat(sut.getLong(0)).isEqualTo(5L);
            assertThat(sut.getLong(9)).isEqualTo(5L);
        }

        @Test
        void foldSumsCorrectly() {
            // Two chunks both constant runs: chunk 0 = 7, chunk 1 = 11. length = 1026.
            long[] values = {7L, 11L};
            int[] indices = new int[2 * 1024];
            long[] valuesIdxOffsets = {0L, 1L};
            var sut = new LazyRleLongArray(I64, 1026, values, indices,
                    valuesIdxOffsets, 0L, 2L, 2, 0);

            long sum = sut.fold(0L, java.lang.Long::sum);

            // 1024 * 7 + 2 * 11 = 7168 + 22 = 7190
            assertThat(sum).isEqualTo(7190L);
        }
    }

    @Nested
    class IntDispatch {

        @Test
        void intLookupAndForEach() {
            int[] values = {100, 200};
            int[] indices = new int[1024];
            indices[0] = 0;
            indices[1] = 1;
            indices[2] = 0;
            long[] valuesIdxOffsets = {0L};
            var sut = new LazyRleIntArray(I32, 3, values, indices,
                    valuesIdxOffsets, 0L, 2L, 1, 0);

            assertThat(sut.getInt(0)).isEqualTo(100);
            assertThat(sut.getInt(1)).isEqualTo(200);
            assertThat(sut.getInt(2)).isEqualTo(100);
        }
    }
}
