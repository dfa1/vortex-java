package io.github.dfa1.vortex.reader.array;

import io.github.dfa1.vortex.core.DType;
import io.github.dfa1.vortex.core.PType;
import io.github.dfa1.vortex.encoding.PTypeIO;
import org.junit.jupiter.api.Test;

import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class LazyForShortArrayTest {

    private static final DType I16 = new DType.Primitive(PType.I16, false);

    private static LazyForShortArray of(short ref, short... encoded) {
        MemorySegment seg = Arena.ofAuto().allocate((long) encoded.length * 2, 2);
        for (int i = 0; i < encoded.length; i++) {
            seg.setAtIndex(PTypeIO.LE_SHORT, i, encoded[i]);
        }
        return new LazyForShortArray(I16, encoded.length, seg, ref);
    }

    @Test
    void getShortAddsRef() {
        // Given — residuals [1, 2, 3] with ref=100 → decoded [101, 102, 103]
        LazyForShortArray sut = of((short) 100, (short) 1, (short) 2, (short) 3);

        // When + Then
        assertThat(sut.getShort(0)).isEqualTo((short) 101);
        assertThat(sut.getShort(1)).isEqualTo((short) 102);
        assertThat(sut.getShort(2)).isEqualTo((short) 103);
    }

    @Test
    void getIntWidensSignedShort() {
        // Given — ref=0, residual=-1 → decoded=-1, getInt returns -1 for I16
        LazyForShortArray sut = of((short) 0, (short) -1);

        // When + Then
        assertThat(sut.getInt(0)).isEqualTo(-1);
    }

    @Test
    void forEachShortVisitsAll() {
        // Given
        LazyForShortArray sut = of((short) 10, (short) 1, (short) 2);
        List<Short> got = new ArrayList<>();

        // When
        sut.forEachShort(got::add);

        // Then
        assertThat(got).containsExactly((short) 11, (short) 12);
    }

    @Test
    void foldSumApplies() {
        // Given — residuals [1,2,3] ref=100 → decoded [101,102,103] → sum 306
        LazyForShortArray sut = of((short) 100, (short) 1, (short) 2, (short) 3);

        // When
        long sum = sut.fold(0L, Long::sum);

        // Then
        assertThat(sum).isEqualTo(306L);
    }
}
