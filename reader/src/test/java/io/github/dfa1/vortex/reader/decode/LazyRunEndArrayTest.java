package io.github.dfa1.vortex.reader.decode;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.reader.array.Array;
import io.github.dfa1.vortex.reader.array.IntArray;
import io.github.dfa1.vortex.reader.array.LongArray;
import io.github.dfa1.vortex.reader.array.MaterializedIntArray;
import io.github.dfa1.vortex.reader.array.MaterializedLongArray;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.util.ArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/// Unit tests for the lazy run-end records nested in [RunEndEncodingDecoder].
/// Covers scalar dispatch via binary-search-on-runEnds, forEach run-walking,
/// fold reduction, and the offset slicing semantics.
class LazyRunEndArrayTest {

    private static final DType I64 = new DType.Primitive(PType.I64, false);
    private static final DType I32 = new DType.Primitive(PType.I32, false);

    @Nested
    class LongDispatch {

        @Test
        void getLongMapsThroughRuns() {
            // Given runs: [0..3)=10, [3..5)=20, [5..8)=30
            try (Arena arena = Arena.ofConfined()) {
                LongArray values = longArray(arena, 10L, 20L, 30L);
                Array runEnds = intArray(arena, 3, 5, 8);
                var sut = new io.github.dfa1.vortex.reader.array.LazyRunEndLongArray(I64, 8L, values, runEnds, 0L);

                // When/Then
                assertThat(sut.getLong(0)).isEqualTo(10L);
                assertThat(sut.getLong(2)).isEqualTo(10L);
                assertThat(sut.getLong(3)).isEqualTo(20L);
                assertThat(sut.getLong(4)).isEqualTo(20L);
                assertThat(sut.getLong(5)).isEqualTo(30L);
                assertThat(sut.getLong(7)).isEqualTo(30L);
            }
        }

        @Test
        void forEachLongWalksRuns() {
            // Given runs: [0..2)=1, [2..3)=2, [3..6)=3
            try (Arena arena = Arena.ofConfined()) {
                LongArray values = longArray(arena, 1L, 2L, 3L);
                Array runEnds = intArray(arena, 2, 3, 6);
                var sut = new io.github.dfa1.vortex.reader.array.LazyRunEndLongArray(I64, 6L, values, runEnds, 0L);

                // When
                var seen = new ArrayList<Long>();
                sut.forEachLong(seen::add);

                // Then
                assertThat(seen).containsExactly(1L, 1L, 2L, 3L, 3L, 3L);
            }
        }

        @Test
        void foldSumsCorrectly() {
            // Given runs: [0..2)=5, [2..5)=10
            try (Arena arena = Arena.ofConfined()) {
                LongArray values = longArray(arena, 5L, 10L);
                Array runEnds = intArray(arena, 2, 5);
                var sut = new io.github.dfa1.vortex.reader.array.LazyRunEndLongArray(I64, 5L, values, runEnds, 0L);

                // When
                long sum = sut.fold(0L, Long::sum);

                // Then 2*5 + 3*10 = 40
                assertThat(sum).isEqualTo(40L);
            }
        }

        @Test
        void offsetSkipsLeadingRuns() {
            // Given runs: [0..3)=1, [3..5)=2, [5..8)=3
            // With offset=3, logical row 0 should map to absolute 3 → value 2
            try (Arena arena = Arena.ofConfined()) {
                LongArray values = longArray(arena, 1L, 2L, 3L);
                Array runEnds = intArray(arena, 3, 5, 8);
                var sut = new io.github.dfa1.vortex.reader.array.LazyRunEndLongArray(I64, 5L, values, runEnds, 3L);

                // When/Then
                assertThat(sut.getLong(0)).isEqualTo(2L);
                assertThat(sut.getLong(1)).isEqualTo(2L);
                assertThat(sut.getLong(2)).isEqualTo(3L);
                assertThat(sut.getLong(4)).isEqualTo(3L);
            }
        }

        @Test
        void offsetForEachStartsAtOffset() {
            try (Arena arena = Arena.ofConfined()) {
                LongArray values = longArray(arena, 1L, 2L, 3L);
                Array runEnds = intArray(arena, 3, 5, 8);
                var sut = new io.github.dfa1.vortex.reader.array.LazyRunEndLongArray(I64, 5L, values, runEnds, 3L);

                var seen = new ArrayList<Long>();
                sut.forEachLong(seen::add);

                // logical [0..5) over runs starting at abs=3: 2,2,3,3,3
                assertThat(seen).containsExactly(2L, 2L, 3L, 3L, 3L);
            }
        }
    }

    @Nested
    class IntDispatch {

        @Test
        void getIntMapsThroughRuns() {
            try (Arena arena = Arena.ofConfined()) {
                IntArray values = intArray(arena, 100, 200);
                Array runEnds = intArray(arena, 3, 5);
                var sut = new io.github.dfa1.vortex.reader.array.LazyRunEndIntArray(I32, 5L, values, runEnds, 0L);

                assertThat(sut.getInt(0)).isEqualTo(100);
                assertThat(sut.getInt(3)).isEqualTo(200);
                assertThat(sut.getInt(4)).isEqualTo(200);
            }
        }
    }

    private static LongArray longArray(Arena arena, long... values) {
        MemorySegment seg = arena.allocate(values.length * 8L, 8);
        for (int i = 0; i < values.length; i++) {
            seg.setAtIndex(ValueLayout.JAVA_LONG, i, values[i]);
        }
        return new MaterializedLongArray(I64, values.length, seg.asReadOnly());
    }

    private static IntArray intArray(Arena arena, int... values) {
        MemorySegment seg = arena.allocate(values.length * 4L, 4);
        for (int i = 0; i < values.length; i++) {
            seg.setAtIndex(ValueLayout.JAVA_INT, i, values[i]);
        }
        return new MaterializedIntArray(I32, values.length, seg.asReadOnly());
    }
}
