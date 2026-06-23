package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.encoding.PTypeIO;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LazyZigZagShortArrayTest {

    private static final DType I16 = DType.I16;

    private static LazyZigZagShortArray of(short... encoded) {
        MemorySegment seg = Arena.ofAuto().allocate((long) encoded.length * 2, 2);
        for (int i = 0; i < encoded.length; i++) {
            seg.setAtIndex(PTypeIO.LE_SHORT, i, encoded[i]);
        }
        return new LazyZigZagShortArray(I16, encoded.length, seg);
    }

    @Test
    void getShortAppliesZigzagDecode() {
        // Given — zigzag(0)=0, zigzag(-1)=1, zigzag(1)=2, zigzag(-2)=3, zigzag(2)=4
        LazyZigZagShortArray sut = of((short) 0, (short) 1, (short) 2, (short) 3, (short) 4);

        // When + Then
        assertThat(sut.getShort(0)).isEqualTo((short) 0);
        assertThat(sut.getShort(1)).isEqualTo((short) -1);
        assertThat(sut.getShort(2)).isEqualTo((short) 1);
        assertThat(sut.getShort(3)).isEqualTo((short) -2);
        assertThat(sut.getShort(4)).isEqualTo((short) 2);
    }

    @Test
    void getIntWidensSignedShort() {
        // Given — zigzag(1) = 2
        LazyZigZagShortArray sut = of((short) 2);

        // When + Then — I16 returns signed int, not zero-extended
        assertThat(sut.getInt(0)).isEqualTo(1);
    }

    @Test
    void forEachShortVisitsAll() {
        // Given
        LazyZigZagShortArray sut = of((short) 4, (short) 5);
        List<Short> got = new ArrayList<>();

        // When
        sut.forEachShort(got::add);

        // Then — zigzag(4)=2, zigzag(5)=-3
        assertThat(got).containsExactly((short) 2, (short) -3);
    }

    @Test
    void foldSumApplies() {
        // Given — encoded [0,1,2,3,4] decodes [0,-1,1,-2,2] → sum 0
        LazyZigZagShortArray sut = of((short) 0, (short) 1, (short) 2, (short) 3, (short) 4);

        // When
        long sum = sut.fold(0L, Long::sum);

        // Then
        assertThat(sum).isZero();
    }
}
