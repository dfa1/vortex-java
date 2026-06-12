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

class DoubleArrayTest {

    private static final ValueLayout.OfDouble LE_DOUBLE =
            ValueLayout.JAVA_DOUBLE_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private static DoubleArray of(double... values) {
        MemorySegment seg = Arena.ofAuto().allocate((long) values.length * 8, 8);
        for (int i = 0; i < values.length; i++) {
            seg.setAtIndex(LE_DOUBLE, i, values[i]);
        }
        DType dtype = new DType.Primitive(PType.F64, false);
        return new DoubleArray(dtype, values.length, seg);
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
            seg.setAtIndex(LE_DOUBLE, 0, 2.71);
            DoubleArray sut = new DoubleArray(new DType.Primitive(PType.F64, false), 3, seg);
            List<Double> collected = new ArrayList<>();

            // When
            sut.forEachDouble(collected::add);

            // Then
            assertThat(collected).containsExactly(2.71, 2.71, 2.71);
        }
    }
}
