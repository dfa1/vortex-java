package io.github.dfa1.vortex.core.array;

import io.github.dfa1.vortex.core.ArrayStats;
import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.nio.ByteOrder;

import static org.assertj.core.api.Assertions.assertThat;

class LongArrayTest {

    private static final ValueLayout.OfLong LE_LONG =
            ValueLayout.JAVA_LONG_UNALIGNED.withOrder(ByteOrder.LITTLE_ENDIAN);

    private static LongArray of(long... values) {
        MemorySegment seg = Arena.ofAuto().allocate((long) values.length * 8, 8);
        for (int i = 0; i < values.length; i++) {
            seg.setAtIndex(LE_LONG, i, values[i]);
        }
        DType dtype = new DType.Primitive(PType.I64, false);
        return new LongArray(dtype, values.length, seg, ArrayStats.empty());
    }

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
}
