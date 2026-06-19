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

class IntArrayTest {

    private static final ValueLayout.OfInt LE_INT =
            ValueLayout.JAVA_INT_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private static IntArray of(int... values) {
        return TestArrays.ints(Arena.ofAuto(), values);
    }

    @Nested
    class ForEachInt {

        @Test
        void visitsAllElementsInOrder() {
            // Given
            IntArray sut = of(10, 20, 30, 40);
            List<Integer> collected = new ArrayList<>();

            // When
            sut.forEachInt(collected::add);

            // Then
            assertThat(collected).containsExactly(10, 20, 30, 40);
        }

        @Test
        void emptyArray_consumerNeverCalled() {
            // Given
            IntArray sut = of();
            List<Integer> collected = new ArrayList<>();

            // When
            sut.forEachInt(collected::add);

            // Then
            assertThat(collected).isEmpty();
        }

        @Test
        void singleElement_consumerCalledOnce() {
            // Given
            IntArray sut = of(42);
            List<Integer> collected = new ArrayList<>();

            // When
            sut.forEachInt(collected::add);

            // Then
            assertThat(collected).containsExactly(42);
        }

        @Test
        void logicalLengthExceedsCapacity_wrapsAround() {
            // Given — constant-encoding: 1-element buffer, logical length 4; all 4 visits yield same value
            MemorySegment seg = Arena.ofAuto().allocate(4, 4);
            seg.setAtIndex(LE_INT, 0, 7);
            IntArray sut = new MaterializedIntArray(new DType.Primitive(PType.I32, false), 4, seg);
            List<Integer> collected = new ArrayList<>();

            // When
            sut.forEachInt(collected::add);

            // Then
            assertThat(collected).containsExactly(7, 7, 7, 7);
        }
    }
}
