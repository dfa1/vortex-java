package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.error.VortexException;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.BoolArray;
import io.github.dfa1.vortex.reader.array.ByteArray;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LazyRunEndBoolArray;
import io.github.dfa1.vortex.reader.array.LazyRunEndByteArray;
import io.github.dfa1.vortex.reader.array.LazyRunEndIntArray;
import io.github.dfa1.vortex.reader.array.LazyRunEndLongArray;
import io.github.dfa1.vortex.reader.array.LazyRunEndShortArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.ShortArray;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;

import static io.github.dfa1.vortex.core.testing.DTypes.BOOL;
import static io.github.dfa1.vortex.core.testing.DTypes.I8;
import static io.github.dfa1.vortex.core.testing.DTypes.I16;
import static io.github.dfa1.vortex.core.testing.DTypes.I32;
import static io.github.dfa1.vortex.core.testing.DTypes.I64;
import static io.github.dfa1.vortex.reader.array.TestArrays.bools;
import static io.github.dfa1.vortex.reader.array.TestArrays.bytes;
import static io.github.dfa1.vortex.reader.array.TestArrays.doubles;
import static io.github.dfa1.vortex.reader.array.TestArrays.ints;
import static io.github.dfa1.vortex.reader.array.TestArrays.longs;
import static io.github.dfa1.vortex.reader.array.TestArrays.shorts;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        void forEachSkipsZeroLengthRun() {
            // Given duplicate run-end → run 1 has zero length (count == 0 branch)
            LongArray values = longs(1L, 99L, 3L);
            Array runEnds = ints(2, 2, 5);
            var sut = new LazyRunEndLongArray(I64, 5L, values, runEnds, 0L);

            // When
            var seen = new ArrayList<Long>();
            sut.forEachLong(seen::add);

            // Then — the zero-length run's value (99) is never emitted
            assertThat(seen).containsExactly(1L, 1L, 3L, 3L, 3L);
        }

        @Test
        void forEachStopsWhenRunsExhaustedBeforeLength() {
            // Given length extends past the last run-end → loop exits on run < numRuns
            LongArray values = longs(1L, 2L);
            Array runEnds = ints(2, 5);
            var sut = new LazyRunEndLongArray(I64, 7L, values, runEnds, 0L);

            // When
            var seen = new ArrayList<Long>();
            sut.forEachLong(seen::add);

            // Then — only the 5 covered rows are emitted
            assertThat(seen).containsExactly(1L, 1L, 2L, 2L, 2L);
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

        @Test
        void forEachIntAndFoldWalkRuns() {
            // Given runs: [0..2)=1, [2..5)=4
            IntArray values = ints(1, 4);
            Array runEnds = ints(2, 5);
            var sut = new LazyRunEndIntArray(I32, 5L, values, runEnds, 0L);

            // When / Then
            var seen = new ArrayList<Integer>();
            sut.forEachInt(seen::add);
            assertThat(seen).containsExactly(1, 1, 4, 4, 4);
            assertThat(sut.fold(0, Integer::sum)).isEqualTo(14); // 2*1 + 3*4
        }

        @Test
        void forEachSkipsZeroLengthRunAndStopsBeyondRuns() {
            // Given a zero-length run (duplicate end) then a length past the last run-end
            IntArray values = ints(1, 99, 3);
            Array dupEnds = ints(2, 2, 5);
            var dup = new LazyRunEndIntArray(I32, 5L, values, dupEnds, 0L);

            // When
            var seen = new ArrayList<Integer>();
            dup.forEachInt(seen::add);

            // Then — zero-length run (99) skipped
            assertThat(seen).containsExactly(1, 1, 3, 3, 3);

            // When — length exceeds last run-end → loop exits on run < numRuns
            var over = new LazyRunEndIntArray(I32, 7L, ints(1, 2), ints(2, 5), 0L);
            var seen2 = new ArrayList<Integer>();
            over.forEachInt(seen2::add);

            // Then
            assertThat(seen2).containsExactly(1, 1, 2, 2, 2);
        }
    }

    @Nested
    class ByteDispatch {

        @Test
        void getByteGetIntFold_withByteRunEnds() {
            // Given runs: [0..2)=1, [2..3)=2, [3..6)=3; run-ends as a ByteArray
            ByteArray values = bytes((byte) 1, (byte) 2, (byte) 3);
            Array runEnds = bytes((byte) 2, (byte) 3, (byte) 6);
            var sut = new LazyRunEndByteArray(I8, 6L, values, runEnds, 0L);

            // When / Then
            assertThat(sut.getByte(0)).isEqualTo((byte) 1);
            assertThat(sut.getByte(2)).isEqualTo((byte) 2);
            assertThat(sut.getByte(5)).isEqualTo((byte) 3);
            assertThat(sut.getInt(5)).isEqualTo(3);
            assertThat(sut.fold(0L, Long::sum)).isEqualTo(13L); // 2*1 + 1*2 + 3*3
        }
    }

    @Nested
    class ShortDispatch {

        @Test
        void getShortGetIntFold_withShortRunEnds() {
            // Given runs: [0..2)=10, [2..5)=20; run-ends as a ShortArray
            ShortArray values = shorts((short) 10, (short) 20);
            Array runEnds = shorts((short) 2, (short) 5);
            var sut = new LazyRunEndShortArray(I16, 5L, values, runEnds, 0L);

            // When / Then
            assertThat(sut.getShort(0)).isEqualTo((short) 10);
            assertThat(sut.getShort(4)).isEqualTo((short) 20);
            assertThat(sut.getInt(4)).isEqualTo(20);
            assertThat(sut.fold(0L, Long::sum)).isEqualTo(80L); // 2*10 + 3*20
        }
    }

    @Nested
    class BoolDispatch {

        @Test
        void getBooleanAndForEach_withLongRunEnds() {
            // Given runs: [0..2)=true, [2..5)=false; run-ends as a LongArray
            BoolArray values = bools(true, false);
            Array runEnds = longs(2L, 5L);
            var sut = new LazyRunEndBoolArray(BOOL, 5L, values, runEnds, 0L);

            // When
            var seen = new ArrayList<Boolean>();
            sut.forEachBoolean(seen::add);

            // Then
            assertThat(sut.getBoolean(0)).isTrue();
            assertThat(sut.getBoolean(2)).isFalse();
            assertThat(seen).containsExactly(true, true, false, false, false);
        }

        @Test
        void forEachSkipsZeroLengthRunAndStopsBeyondRuns() {
            // Given — duplicate run-end yields a zero-length run (RunEndArrays.walkRuns count == 0)
            var dup = new LazyRunEndBoolArray(BOOL, 5L, bools(true, false, true),
                    longs(2L, 2L, 5L), 0L);

            // When
            var seen = new ArrayList<Boolean>();
            dup.forEachBoolean(seen::add);

            // Then — the zero-length run is skipped
            assertThat(seen).containsExactly(true, true, true, true, true);

            // When — length past the last run-end → walkRuns exits on run < numRuns
            var over = new LazyRunEndBoolArray(BOOL, 7L, bools(true, false), longs(2L, 5L), 0L);
            var seen2 = new ArrayList<Boolean>();
            over.forEachBoolean(seen2::add);

            // Then
            assertThat(seen2).containsExactly(true, true, false, false, false);
        }
    }

    @Nested
    class InvalidRunEnds {

        @Test
        void unsupportedRunEndsType_throws() {
            // Given run-ends backed by a DoubleArray — not a supported run-ends type
            LongArray values = longs(1L, 2L);
            Array badRunEnds = doubles(2.0, 5.0);
            var sut = new LazyRunEndLongArray(I64, 5L, values, badRunEnds, 0L);

            // When / Then — the binary search reads run-ends and hits the default arm
            assertThatThrownBy(() -> sut.getLong(0))
                    .isInstanceOf(VortexException.class)
                    .hasMessageContaining("run-ends");
        }
    }
}
