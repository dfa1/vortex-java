package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.core.model.DType;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.util.List;

import static io.github.dfa1.vortex.reader.array.TestArrays.ints;
import static io.github.dfa1.vortex.reader.array.TestArrays.longs;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/// Covers [ChunkedArrayCombiner], the dtype-driven stitcher for multi-chunk columns. The focus is
/// the list branch and the security guard for issue #268: a chunked `list<int>` column previously hit
/// a blind `(DType.Primitive) dtype` cast in `ChunkedLayoutDecoder`, throwing a raw
/// `ClassCastException` instead of a handled [VortexException].
class ChunkedArrayCombinerTest {

    private static final DType.List LIST_OF_INT = new DType.List(DType.I32, false);

    @Test
    void stitchesMultiChunkListIntoOneListArray() {
        // Given — two chunks of list<int>, each with offsets that restart at 0.
        // chunk0: [ [10,11], [12] ]   chunk1: [ [20,21,22], [] ]
        ListArray chunk0 = new ListArray(LIST_OF_INT, 2, ints(10, 11, 12), longs(0L, 2L, 3L));
        ListArray chunk1 = new ListArray(LIST_OF_INT, 2, ints(20, 21, 22), longs(0L, 3L, 3L));

        // When
        try (Arena arena = Arena.ofConfined()) {
            Array result = ChunkedArrayCombiner.combine(LIST_OF_INT, 4, List.of(chunk0, chunk1), arena);

            // Then — a single ListArray with cumulative offsets and concatenated elements. The
            // second chunk's offsets are shifted by chunk0's 3 elements, so list2 spans [3,6).
            assertThat(result).isInstanceOf(ListArray.class);
            ListArray list = (ListArray) result;
            assertThat(list.length()).isEqualTo(4);
            LongArray offsets = (LongArray) list.offsets();
            assertThat(offsets.length()).isEqualTo(5);
            assertThat(readAll(offsets, 5)).containsExactly(0L, 2L, 3L, 6L, 6L);

            IntArray elements = (IntArray) list.elements();
            assertThat(elements.length()).isEqualTo(6);
            assertThat(elements.getInt(0)).isEqualTo(10);
            assertThat(elements.getInt(3)).isEqualTo(20);
            assertThat(elements.getInt(5)).isEqualTo(22);
        }
    }

    @Test
    void nullableListChunksKeepTheirNulls() {
        // Given — a nullable list<int> column across two chunks: chunk0 row 1 is null, chunk1 row 0
        // is null. The ChunkedXxxArray.of flatteners drop per-chunk validity, so the combiner must
        // rebuild it into one row-level bitmap wrapped in a MaskedArray (else the nulls vanish when
        // the column spans more than one chunk).
        ListArray data0 = new ListArray(LIST_OF_INT, 2, ints(10, 11), longs(0L, 2L, 2L));
        ListArray data1 = new ListArray(LIST_OF_INT, 2, ints(20), longs(0L, 0L, 1L));
        MaskedArray chunk0 = new MaskedArray(data0, TestArrays.bools(true, false));
        MaskedArray chunk1 = new MaskedArray(data1, TestArrays.bools(false, true));

        // When
        try (Arena arena = Arena.ofConfined()) {
            Array result = ChunkedArrayCombiner.combine(LIST_OF_INT, 4, List.of(chunk0, chunk1), arena);

            // Then — the combined validity marks rows 1 and 2 null.
            assertThat(result).isInstanceOf(MaskedArray.class);
            MaskedArray masked = (MaskedArray) result;
            assertThat(masked.inner()).isInstanceOf(ListArray.class);
            assertThat(masked.isValid(0)).isTrue();
            assertThat(masked.isValid(1)).isFalse();
            assertThat(masked.isValid(2)).isFalse();
            assertThat(masked.isValid(3)).isTrue();
        }
    }

    @Test
    void allNullNullArrayChunkCombinesAsZeroLengthLists() {
        // Given — a chunked list<int> column whose middle chunk decoded to a bare NullArray, not a
        // ListArray (a vortex.null flat or vortex.constant null-scalar chunk, #269). Before the fix
        // combineLists hit its else branch and threw "chunk is not a ListArray: NullArray"; the
        // #269-parity fix must treat those rows as zero-length lists carrying out-of-band nulls.
        ListArray chunk0 = new ListArray(LIST_OF_INT, 2, ints(10, 11, 12), longs(0L, 2L, 3L));
        NullArray chunk1 = new NullArray(LIST_OF_INT, 2);
        ListArray chunk2 = new ListArray(LIST_OF_INT, 1, ints(20, 21), longs(0L, 2L));

        // When
        try (Arena arena = Arena.ofConfined()) {
            Array result = ChunkedArrayCombiner.combine(LIST_OF_INT, 5,
                    List.of(chunk0, chunk1, chunk2), arena);

            // Then — a NullArray chunk makes the whole column nullable, so the combiner wraps the
            // stitched ListArray in a MaskedArray marking the null chunk's rows invalid while the
            // real chunks' rows stay valid and their elements/offsets stay intact.
            assertThat(result).isInstanceOf(MaskedArray.class);
            MaskedArray masked = (MaskedArray) result;
            assertThat(masked.inner()).isInstanceOf(ListArray.class);
            assertThat(masked.isValid(0)).isTrue();
            assertThat(masked.isValid(1)).isTrue();
            assertThat(masked.isValid(2)).isFalse();
            assertThat(masked.isValid(3)).isFalse();
            assertThat(masked.isValid(4)).isTrue();

            ListArray list = (ListArray) masked.inner();
            assertThat(list.length()).isEqualTo(5);
            LongArray offsets = (LongArray) list.offsets();
            // Rows 0-1 span [0,2) and [2,3); the null rows 2-3 are zero-length (offset stays at 3);
            // row 4 (chunk2) contributes 2 elements, shifted past chunk0's 3 to span [3,5).
            assertThat(readAll(offsets, 6)).containsExactly(0L, 2L, 3L, 3L, 3L, 5L);

            IntArray elements = (IntArray) list.elements();
            assertThat(elements.length()).isEqualTo(5);
            assertThat(elements.getInt(0)).isEqualTo(10);
            assertThat(elements.getInt(3)).isEqualTo(20);
            assertThat(elements.getInt(4)).isEqualTo(21);
        }
    }

    @Test
    void emptyChunkListThrowsVortexException() {
        // Given / When / Then
        try (Arena arena = Arena.ofConfined()) {
            assertThatThrownBy(() -> ChunkedArrayCombiner.combine(LIST_OF_INT, 0, List.of(), arena))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("empty chunk list");
        }
    }

    @Test
    void rowCountMismatchThrowsVortexException() {
        // Given — the declared totalRows disagrees with the summed chunk lengths.
        ListArray chunk = new ListArray(LIST_OF_INT, 2, ints(10, 11, 12), longs(0L, 2L, 3L));

        // When / Then
        try (Arena arena = Arena.ofConfined()) {
            assertThatThrownBy(() -> ChunkedArrayCombiner.combine(LIST_OF_INT, 99, List.of(chunk, chunk), arena))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("expected 99");
        }
    }

    @Test
    void unsupportedDtypeThrowsVortexExceptionNotClassCastException() {
        // Given — a dtype with no chunked shape (a nested struct). The pre-#268 code path blindly
        // cast to DType.Primitive and threw a raw ClassCastException; the guard must be a
        // VortexException instead.
        DType.Struct struct = new DType.Struct(List.of(), List.of(), false);
        Array chunk0 = ints(1);
        Array chunk1 = ints(2);

        // When / Then
        try (Arena arena = Arena.ofConfined()) {
            assertThatThrownBy(() -> ChunkedArrayCombiner.combine(struct, 2, List.of(chunk0, chunk1), arena))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("unsupported dtype for chunked layout");
        }
    }

    private static long[] readAll(LongArray arr, int n) {
        long[] out = new long[n];
        for (int i = 0; i < n; i++) {
            out[i] = arr.getLong(i);
        }
        return out;
    }
}
