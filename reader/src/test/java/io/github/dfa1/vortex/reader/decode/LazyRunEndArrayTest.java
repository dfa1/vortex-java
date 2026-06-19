package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LazyRunEndIntArray;
import io.github.dfa1.vortex.reader.array.LazyRunEndLongArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static io.github.dfa1.vortex.encoding.DTypes.I32;
import static io.github.dfa1.vortex.encoding.DTypes.I64;
import static io.github.dfa1.vortex.reader.array.TestArrays.ints;
import static io.github.dfa1.vortex.reader.array.TestArrays.longs;
import static org.assertj.core.api.Assertions.assertThat;

/// Unit tests for the lazy run-end records nested in [RunEndEncodingDecoder].
/// Covers scalar dispatch via binary-search-on-runEnds, forEach run-walking,
/// fold reduction, and the offset slicing semantics.
class LazyRunEndArrayTest {

    @Nested
    class LongDispatch {

        @Test
        void getLongMapsThroughRuns() {
            // Given runs: [0..3)=10, [3..5)=20, [5..8)=30
            LongArray values = longs(10L, 20L, 30L);
            Array runEnds = ints(3, 5, 8);
            var sut = new LazyRunEndLongArray(I64, 8L, values, runEnds, 0L);

            // When / Then
            assertThat(sut.getLong(0)).isEqualTo(10L);
            assertThat(sut.getLong(2)).isEqualTo(10L);
            assertThat(sut.getLong(3)).isEqualTo(20L);
            assertThat(sut.getLong(4)).isEqualTo(20L);
            assertThat(sut.getLong(5)).isEqualTo(30L);
            assertThat(sut.getLong(7)).isEqualTo(30L);
        }

        @Test
        void forEachLongWalksRuns() {
            // Given runs: [0..2)=1, [2..3)=2, [3..6)=3
            LongArray values = longs(1L, 2L, 3L);
            Array runEnds = ints(2, 3, 6);
            var sut = new LazyRunEndLongArray(I64, 6L, values, runEnds, 0L);

            // When
            var seen = new ArrayList<Long>();
            sut.forEachLong(seen::add);

            // Then
            assertThat(seen).containsExactly(1L, 1L, 2L, 3L, 3L, 3L);
        }

        @Test
        void foldSumsCorrectly() {
            // Given runs: [0..2)=5, [2..5)=10
            LongArray values = longs(5L, 10L);
            Array runEnds = ints(2, 5);
            var sut = new LazyRunEndLongArray(I64, 5L, values, runEnds, 0L);

            // When
            long result = sut.fold(0L, Long::sum);

            // Then — 2*5 + 3*10 = 40
            assertThat(result).isEqualTo(40L);
        }

        @Test
        void offsetSkipsLeadingRuns() {
            // Given runs: [0..3)=1, [3..5)=2, [5..8)=3; offset=3 maps logical 0 -> abs 3 -> value 2
            LongArray values = longs(1L, 2L, 3L);
            Array runEnds = ints(3, 5, 8);
            var sut = new LazyRunEndLongArray(I64, 5L, values, runEnds, 3L);

            // When / Then
            assertThat(sut.getLong(0)).isEqualTo(2L);
            assertThat(sut.getLong(1)).isEqualTo(2L);
            assertThat(sut.getLong(2)).isEqualTo(3L);
            assertThat(sut.getLong(4)).isEqualTo(3L);
        }

        @Test
        void offsetForEachStartsAtOffset() {
            // Given runs [0..3)=1,[3..5)=2,[5..8)=3 with offset 3
            LongArray values = longs(1L, 2L, 3L);
            Array runEnds = ints(3, 5, 8);
            var sut = new LazyRunEndLongArray(I64, 5L, values, runEnds, 3L);

            // When
            var seen = new ArrayList<Long>();
            sut.forEachLong(seen::add);

            // Then — logical [0..5) over runs starting at abs=3: 2,2,3,3,3
            assertThat(seen).containsExactly(2L, 2L, 3L, 3L, 3L);
        }
    }

    @Nested
    class IntDispatch {

        @Test
        void getIntMapsThroughRuns() {
            // Given runs: [0..3)=100, [3..5)=200
            IntArray values = ints(100, 200);
            Array runEnds = ints(3, 5);
            var sut = new LazyRunEndIntArray(I32, 5L, values, runEnds, 0L);

            // When / Then
            assertThat(sut.getInt(0)).isEqualTo(100);
            assertThat(sut.getInt(3)).isEqualTo(200);
            assertThat(sut.getInt(4)).isEqualTo(200);
        }
    }
}
