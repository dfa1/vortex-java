package io.github.dfa1.vortex.reader.array;


import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LongArrayTest {

    private static final ValueLayout.OfLong LE_LONG =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private static LongArray of(long... values) {
        return TestArrays.longs(Arena.ofAuto(), values);
    }

    @Nested
    class ForEachLong {

        @Test
        void visitsAllElementsInOrder() {
            // Given
            LongArray sut = of(100L, 200L, 300L);
            List<Long> collected = new ArrayList<>();

            // When
            sut.forEachLong(collected::add);

            // Then
            assertThat(collected).containsExactly(100L, 200L, 300L);
        }

        @Test
        void emptyArray_consumerNeverCalled() {
            // Given
            LongArray sut = of();
            List<Long> collected = new ArrayList<>();

            // When
            sut.forEachLong(collected::add);

            // Then
            assertThat(collected).isEmpty();
        }

        @Test
        void singleElement_consumerCalledOnce() {
            // Given
            LongArray sut = of(99L);
            List<Long> collected = new ArrayList<>();

            // When
            sut.forEachLong(collected::add);

            // Then
            assertThat(collected).containsExactly(99L);
        }

        @Test
        void logicalLengthExceedsCapacity_wrapsAround() {
            // Given — constant-encoding: 1-element buffer, logical length 4; all 4 visits yield same value
            MemorySegment seg = Arena.ofAuto().allocate(8, 8);
            seg.setAtIndex(LE_LONG, 0, 42L);
            LongArray sut = new MaterializedLongArray(new DType.Primitive(PType.I64, false), 4, seg);
            List<Long> collected = new ArrayList<>();

            // When
            sut.forEachLong(collected::add);

            // Then
            assertThat(collected).containsExactly(42L, 42L, 42L, 42L);
        }
    }

    @Nested
    class Fold {

    @Test
    void fold_sum_returnsCorrectTotal() {
        // Given
        LongArray sut = of(1L, 2L, 3L, 4L, 5L);

        // When
        long result = sut.fold(0L, Long::sum);

        // Then
        assertThat(result).isEqualTo(15L);
    }

    @Test
    void fold_max_returnsLargestValue() {
        // Given
        LongArray sut = of(3L, 1L, 4L, 1L, 5L, 9L, 2L);

        // When
        long result = sut.fold(Long.MIN_VALUE, Math::max);

        // Then
        assertThat(result).isEqualTo(9L);
    }

    @Test
    void fold_min_returnsSmallestValue() {
        // Given
        LongArray sut = of(3L, 1L, 4L, 1L, 5L, 9L, 2L);

        // When
        long result = sut.fold(Long.MAX_VALUE, Math::min);

        // Then
        assertThat(result).isEqualTo(1L);
    }

    @Test
    void fold_emptyArray_returnsIdentity() {
        // Given
        LongArray sut = of();

        // When
        long result = sut.fold(42L, Long::sum);

        // Then
        assertThat(result).isEqualTo(42L);
    }

    } // Fold
}
