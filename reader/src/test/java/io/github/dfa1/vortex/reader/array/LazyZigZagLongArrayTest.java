package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.io.VortexFormat;
import io.github.dfa1.vortex.core.model.DType;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LazyZigZagLongArrayTest {


    private static final DType I64 = DType.I64;

    private static LazyZigZagLongArray of(long... encoded) {
        MemorySegment seg = Arena.ofAuto().allocate((long) encoded.length * 8, 8);
        for (int i = 0; i < encoded.length; i++) {
            seg.setAtIndex(VortexFormat.LE_LONG, i, encoded[i]);
        }
        return new LazyZigZagLongArray(I64, encoded.length, seg);
    }

    @Test
    void getLongAppliesZigzagDecode() {
        // Given — zigzag(0)=0, zigzag(-1)=1, zigzag(1)=2, zigzag(-2)=3, zigzag(2)=4
        LazyZigZagLongArray sut = of(0L, 1L, 2L, 3L, 4L);

        // When + Then
        assertThat(sut.getLong(0)).isZero();
        assertThat(sut.getLong(1)).isEqualTo(-1L);
        assertThat(sut.getLong(2)).isEqualTo(1L);
        assertThat(sut.getLong(3)).isEqualTo(-2L);
        assertThat(sut.getLong(4)).isEqualTo(2L);
    }

    @Test
    void forEachLongVisitsAll() {
        // Given
        LazyZigZagLongArray sut = of(2L, 3L);
        List<Long> got = new ArrayList<>();

        // When
        sut.forEachLong(got::add);

        // Then
        assertThat(got).containsExactly(1L, -2L);
    }

    @Test
    void foldSumApplies() {
        // Given — encoded [0,1,2,3,4] decodes [0,-1,1,-2,2] → sum 0
        LazyZigZagLongArray sut = of(0L, 1L, 2L, 3L, 4L);

        // When
        long sum = sut.fold(0L, Long::sum);

        // Then
        assertThat(sum).isZero();
    }
}
