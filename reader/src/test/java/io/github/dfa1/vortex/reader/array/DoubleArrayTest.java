package io.github.dfa1.vortex.reader.array;


import io.github.dfa1.vortex.core.io.PTypeIO;
import io.github.dfa1.vortex.core.model.DType;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DoubleArrayTest {


    private static DoubleArray of(double... values) {
        return TestArrays.doubles(values);
    }

    @Nested
    class ForEachDouble {

        @Test
        void visitsAllElementsInOrder() {
            // Given
            DoubleArray sut = of(1.1, 2.2, 3.3, 4.4);
            List<Double> collected = new ArrayList<>();

            // When
            sut.forEachDouble(collected::add);

            // Then
            assertThat(collected).containsExactly(1.1, 2.2, 3.3, 4.4);
        }

        @Test
        void emptyArray_consumerNeverCalled() {
            // Given
            DoubleArray sut = of();
            List<Double> collected = new ArrayList<>();

            // When
            sut.forEachDouble(collected::add);

            // Then
            assertThat(collected).isEmpty();
        }

        @Test
        void singleElement_consumerCalledOnce() {
            // Given
            DoubleArray sut = of(3.14);
            List<Double> collected = new ArrayList<>();

            // When
            sut.forEachDouble(collected::add);

            // Then
            assertThat(collected).containsExactly(3.14);
        }

        @Test
        void logicalLengthExceedsCapacity_wrapsAround() {
            // Given — constant-encoding: 1-element buffer, logical length 3; all 3 visits yield same value
            MemorySegment seg = Arena.ofAuto().allocate(8, 8);
            seg.setAtIndex(PTypeIO.LE_DOUBLE, 0, 2.71);
            DoubleArray sut = new MaterializedDoubleArray(DType.F64, 3, seg);
            List<Double> collected = new ArrayList<>();

            // When
            sut.forEachDouble(collected::add);

            // Then
            assertThat(collected).containsExactly(2.71, 2.71, 2.71);
        }
    }
}
